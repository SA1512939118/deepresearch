/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.deepresearch.controller.graph;

import com.alibaba.cloud.ai.example.deepresearch.model.enums.NodeNameEnum;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.StreamNodePrefixEnum;
import com.alibaba.cloud.ai.example.deepresearch.model.req.ChatRequest;
import com.alibaba.cloud.ai.example.deepresearch.model.req.GraphId;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.async.AsyncGenerator;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Optional;
import java.util.concurrent.Future;

/**
 * @author yingzi
 * @since 2025/6/6 15:05
 */

public class GraphProcess {

	/**
	 * 跟踪每个会话的图示例计数，用于生成唯一的图ID
	 */
	private final ConcurrentHashMap<String, Integer> sessionCountMap = new ConcurrentHashMap<>();

	/**
	 * 管理运行中的图任务，支持任务取消
	 */
	private final ConcurrentHashMap<GraphId, Future<?>> graphTaskFutureMap = new ConcurrentHashMap<>();

	private static final Logger logger = LoggerFactory.getLogger(GraphProcess.class);

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	// 任务被中断时发送给前端的信息
	public static final String TASK_STOPPED_MESSAGE_TEMPLATE = "{\"nodeName\": \"__END__\",\"graphId\": %s, \"displayTitle\": \"结束\", \"content\": { \"reason\": \"%s\"}} ";

	// 线程数需要大于2，使用固定大小线程池，避免阻塞主线程。
	// 为什么可以避免阻塞主线程？因为主线程（通常是HTTP请求处理线程）只负责提交任务到线程池，实际执行由线程池中的工作线程完成，主线程提交任务后立即返回，不会被阻塞等待任务完成
	// 为什么线程数需要大于2？图执行过程中可能需要多个并发操作，如果线程数只有 1-2 个，可能导致：
	//线程饥饿：所有线程都在等待某个操作完成
	//死锁风险：线程互相等待资源
	//性能下降：无法充分利用多核 CPU
	private final ExecutorService executor = Executors.newFixedThreadPool(10);

	private final CompiledGraph compiledGraph;

	public GraphProcess(CompiledGraph compiledGraph) {
		this.compiledGraph = compiledGraph;
	}

	/**
	 * 一个会话中可能并行或串行地执行多个“图”，每个图对应一条逻辑线程（用 threadId 来唯一标识）
	 * @param sessionId
	 * @return
	 */
	public GraphId createNewGraphId(String sessionId) {
		if (StringUtils.isEmpty(sessionId)) {
			throw new IllegalArgumentException("Session Id is empty");
		}
		int count = sessionCountMap.merge(sessionId, 1, Integer::sum);
		return new GraphId(sessionId, String.format("%s-%d", sessionId, count));
	}

	/**
	 * @param graphId 图的唯一标识符，用于追踪特定的图执行实例
	 * @param chatRequest 包含用户反馈信息的请求对象
	 * @param objectMap 上下文数据映射，用于传递状态信息
	 * @param runnableConfig 图的运行配置，包含执行上下文
	 * @param sink 响应式流输出，用于向前端推送实时结果
	 * @throws GraphRunnerException
	 *
	 * 执行流程图：
	 * 用户反馈
	 *     ↓
	 * 存储到 objectMap
	 *     ↓
	 * 获取状态快照
	 *     ↓
	 * 提取状态对象
	 *     ↓
	 * 标记为恢复模式
	 *     ↓
	 * 附加人类反馈
	 *     ↓
	 * 创建响应式流
	 *     ↓
	 * 处理流并推送结果
	 *     ↓
	 * 前端实时接收
	 *
	 * 这个方法是现代AI应用架构的典范，它展示了如何：
	 * 将复杂的AI工作流与人类决策结合
	 * 使用响应式编程处理异步任务
	 * 通过状态管理实现可恢复的执行流程
	 * 构建实时、交互式的用户体验
	 */
	public void handleHumanFeedback(GraphId graphId, ChatRequest chatRequest, Map<String, Object> objectMap,
			RunnableConfig runnableConfig, Sinks.Many<ServerSentEvent<String>> sink) throws GraphRunnerException {
		// 将用户的反馈信息存储到上下文映射中，键为 "feedback"，值为从请求中提取的中断反馈内容
		objectMap.put("feedback", chatRequest.interruptFeedback());


		/**
		 * 获取当前图的状态快照。这是一个关键设计决策：
		 * 使用快照模式保存执行状态
		 * 支持从任意中断点恢复执行
		 * 实现了状态的可持久化和可恢复性
		 */
		StateSnapshot stateSnapshot = compiledGraph.getState(runnableConfig);

		// 从快照中提取实际的状态对象，这是图执行的核心状态容器
		OverAllState state = stateSnapshot.state();

		// 标记状态为"恢复模式"，告诉图引擎这不是一次新的执行，而是从中断点继续
		state.withResume();

		/**
		 * 将人类反馈附加到状态中：
		 * 创建 HumanFeedback 对象，封装反馈数据和目标节点
		 * 指定反馈的目标是 "research_team" 节点
		 * 这是状态机模式的体现，通过状态变更驱动流程
		 */
		state.withHumanFeedback(new OverAllState.HumanFeedback(objectMap, "research_team"));

		/**
		 * 从当前状态重新启动图的执行流：
		 * 使用响应式编程（Reactor的Flux）
		 * 从初始节点开始，但携带了之前的上下文和反馈
		 * 返回一个异步流，可以逐步处理节点输出
		 */
		Flux<NodeOutput> resultFuture = compiledGraph.fluxStreamFromInitialNode(state, runnableConfig);

		/**
		 * 将结果流传递给流处理器，负责：
		 * 将节点输出转换为SSE事件
		 * 向前端推送实时结果
		 * 处理异常和完成状态
		 */
		processStream(graphId, resultFuture, sink);
	}

	private String safeObjectToJson(Object object) {
		try {
			return OBJECT_MAPPER.writeValueAsString(object);
		}
		catch (JsonProcessingException e) {
			logger.error("JSON processing error: {}", e.getMessage());
			return "{}";
		}
	}

	/**
	 * 这个 processStream 方法是图执行流处理的核心组件，负责将异步的图执行结果转换为实时推送的SSE（Server-Sent Events）事件流。它实现了从后端图引擎到前端UI的实时数据传输桥梁。
	 *
	 * 核心职责：
	 * 异步任务管理：将图执行任务提交到线程池
	 * 流式数据处理：处理响应式流（Flux）中的每个节点输出
	 * 事件转换：将节点输出转换为SSE事件格式
	 * 生命周期管理：处理完成、错误和中断状态
	 * 任务追踪：维护运行中任务的映射关系
	 *
	 * @param graphId 图的唯一标识符，用于追踪特定执行实例
	 * @param generator 响应式流，产生图的节点输出
	 * @param sink 响应式Sink，用于向客户端推送SSE事件
	 *
	 * 整体执行流程图
	 * 1. 提交任务到线程池
	 *    ↓
	 * 2. 线程池分配工作线程
	 *    ↓
	 * 3. 工作线程执行Lambda表达式
	 *    ↓
	 * 4. 创建响应式流订阅
	 *    ↓
	 * 5. 流开始产生数据
	 *    ↓
	 * 6. 处理每个输出元素
	 *    ↓
	 * 7. 处理完成或错误
	 *    ↓
	 * 8. 将Future存入Map
	 *
	 *
	 *
	 *  完整执行时序图
	 *  时间线 →
	 *
	 * 主线程                    工作线程                    前端
	 *   |                          |                          |
	 *   |-- executor.submit() ---->|                          |
	 *   |                          |                          |
	 *   |<-- 返回 Future ----------|                          |
	 *   |                          |                          |
	 *   |-- graphTaskFutureMap     |                          |
	 *   |   .put(graphId, future)  |                          |
	 *   |                          |                          |
	 *   |  (主线程继续处理其他请求) |                          |
	 *   |                          |                          |
	 *   |                          |-- .subscribe() -------->|
	 *   |                          |  (启动流)                |
	 *   |                          |                          |
	 *   |                          |-- generator.next() ---->|
	 *   |                          |  (产生数据)              |
	 *   |                          |                          |
	 *   |                          |-- doOnNext() ----------->|
	 *   |                          |  (处理数据)              |
	 *   |                          |                          |
	 *   |                          |-- sink.tryEmitNext() --->|
	 *   |                          |  (推送SSE) ------------->|
	 *   |                          |                          |<-- 接收数据
	 *   |                          |                          |
	 *   |                          |-- generator.next() ---->|
	 *   |                          |  (产生下一个数据)        |
	 *   |                          |                          |
	 *   |                          |-- doOnNext() ----------->|
	 *   |                          |                          |
	 *   |                          |-- sink.tryEmitNext() --->|
	 *   |                          |                          |<-- 接收数据
	 *   |                          |                          |
	 *   |                          |-- (所有数据处理完毕)     |
	 *   |                          |                          |
	 *   |                          |-- doOnComplete() ------->|
	 *   |                          |                          |
	 *   |                          |-- sink.tryEmitComplete() >|
	 *   |                          |                          |<-- 流结束
	 *   |                          |                          |
	 *   |                          |-- graphTaskFutureMap     |
	 *   |                          |   .remove(graphId)       |
	 *   |                          |                          |
	 *   |                          |-- 任务结束               |
	 */
	public void processStream(GraphId graphId, Flux<NodeOutput> generator, Sinks.Many<ServerSentEvent<String>> sink) {
		final String graphIdStr = this.safeObjectToJson(graphId);
		// 创建一个任务，且遇见中断时停止图的运行。这里使用了观察者模式，generator是被观察者，doOnNext、doOnComplete、doOnError 是观察者回调
		Future<?> future = executor.submit(() -> {
			generator.doOnNext(output -> {
				String nodeName = output.node();
				String content;
				if (output instanceof StreamingOutput streamingOutput) {
					// 流式输出（如LLM的token流）
					logger.debug("Streaming output from node {}: {}, {}", nodeName, streamingOutput.chunk(), graphId);
					// 处理流式LLM输出
					content = buildLLMNodeContent(nodeName, graphId, streamingOutput, output);
				}
				else {
					// 完整节点输出
					logger.debug("Normal output from node {}", nodeName);
					// 处理普通节点输出
					content = buildNormalNodeContent(graphId, nodeName, output);
				}
				if (StringUtils.isNotEmpty(content)) {
					sink.tryEmitNext(ServerSentEvent.builder(content).build());
				}
			}).doOnComplete(() -> {
				logger.info("Stream processing completed.");
				// 通知客户端流结束
				sink.tryEmitComplete();
				// 完成后从任务Map中移出，防止Map无线增长导致内存邪路，保持Map中只包含“正在运行”的任务
				graphTaskFutureMap.remove(graphId);
			}).doOnError(e -> {
				logger.error("Error in stream processing", e);
				// 发送格式化的错误消息给前端
				sink.tryEmitNext(
						ServerSentEvent.builder(String.format(TASK_STOPPED_MESSAGE_TEMPLATE, graphIdStr, "服务异常"))
							.build());
				// 错误传播，通过tryEmitError 传播异常，触发客户端错误处理
				sink.tryEmitError(e);
			}).subscribe();
			/**
			 * .subscribe() 做了什么？启动订阅时，流才会真正开始工作
			 * 激活流：从"冷"状态变为"热"状态
			 * 开始产生数据：generator 开始产生 NodeOutput 对象
			 * 触发回调：
			 * 每产生一个数据 → 触发 doOnNext
			 * 流正常结束 → 触发 doOnComplete
			 * 流发生错误 → 触发 doOnError
			 */
		});
		// 将任务存放到Map中，此时任务正在线程池中运行
		Future<?> oldFuture = graphTaskFutureMap.put(graphId, future);

		// 检测是否有相同graphId的任务还在运行，避免重复执行相同的任务
		Optional.ofNullable(oldFuture).ifPresent((f) -> {
			if (!f.isDone()) {
				logger.warn("A task with the same GraphId {} is still running!", graphId);
			}
		});
	}

	/**
	 *
	 * @param graphId
	 * @param generator
	 * @param sink
	 *
	 * 手动中断检测，线程管理有三层：外层+循环+内层，频繁手动创建线程，上下文切换频繁，浪费资源
	 */
	@Deprecated
	public void processStream(GraphId graphId, AsyncGenerator<NodeOutput> generator,
			Sinks.Many<ServerSentEvent<String>> sink) {
		// 使用final确保线程安全，提前序列化graphId，避免在异步回调中重复操作
		final String graphIdStr = this.safeObjectToJson(graphId);

		// 创建一个任务，且遇见中断时停止图的运行
		Future<?> future = executor.submit(() -> {
			AsyncGenerator.Data<NodeOutput> next;

			while (true) {
				NodeOutput output;
				// 另外发起一个线程，用于迭代generator，因为generator::next是一个可能阻塞的操作（比如等待LLM响应），所以在单独的线程中执行，避免阻塞外层线程
				Future<AsyncGenerator.Data<NodeOutput>> nextFuture = executor.submit(generator::next);
				try {
					// 这里会阻塞外层线程，因为需要等待内层线程完成generator::next操作，异步生成器的next()方法本质上是阻塞的，他会等待下一个数据就绪，这意味着外层线程必须等到内层线程完成数据获取，导致线程利用率低下
					next = nextFuture.get();// 这里为什么会阻塞
					if (next.isDone()) {
						break;
					}

					if (next.isError()) {
						// 处理节点执行异常，确保异常能够正确传播并阻止重试
						Throwable error = null;

						try {
							next.getData().get();
						}
						catch (ExecutionException ee) {
							error = ee.getCause() != null ? ee.getCause() : ee;// 异常链提取: 从 ExecutionException 中提取根本原因
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();// 中断处理：恢复线程中断状态（最佳实践）
							error = ie;
						}
						catch (Exception e) {
							error = e;
						}

						// 确保我们获得了异常信息
						if (error == null) {
							error = new RuntimeException("Unknown node execution error");
						}

						logger.error("Node execution error detected: {}", error.getMessage(), error);

						// 发送错误信息给前端
						sink.tryEmitNext(ServerSentEvent
							.builder(String.format(TASK_STOPPED_MESSAGE_TEMPLATE, graphIdStr,
									"节点执行异常: " + error.getMessage()))
							.build());

						// 向 sink 传播错误，触发错误处理链
						sink.tryEmitError(error);

						// 从任务Map中移除，防止后续重试
						graphTaskFutureMap.remove(graphId);
						return;
					}

					// 获取NodeOutput
					output = next.getData().get();
				}
				catch (CancellationException | ExecutionException e) {
					logger.error("Error in stream processing", e);
					sink.tryEmitNext(
							ServerSentEvent.builder(String.format(TASK_STOPPED_MESSAGE_TEMPLATE, graphIdStr, "服务异常"))
								.build());
					sink.tryEmitError(e);
					return;
				}
				catch (InterruptedException e) {
					logger.info("Stopped by user.");
					sink.tryEmitNext(
							ServerSentEvent.builder(String.format(TASK_STOPPED_MESSAGE_TEMPLATE, graphIdStr, "用户终止"))
								.build());
					sink.tryEmitComplete();
					return;
				}

				String nodeName = output.node();
				String content;
				if (output instanceof StreamingOutput streamingOutput) {
					logger.debug("Streaming output from node {}: {}, {}", nodeName,
							streamingOutput.chatResponse().getResult().getOutput().getText(), graphId);

					content = buildLLMNodeContent(nodeName, graphId, streamingOutput, output);
				}
				else {
					logger.debug("Normal output from node {}: {}", nodeName, output.state().value("messages"));
					content = buildNormalNodeContent(graphId, nodeName, output);
				}
				if (StringUtils.isNotEmpty(content)) {
					sink.tryEmitNext(ServerSentEvent.builder(content).build());
				}

				// 检测任务是否被中断
				if (Thread.currentThread().isInterrupted()) {
					logger.info("Stopped by user at node: {}", nodeName);
					sink.tryEmitNext(
							ServerSentEvent.builder(String.format(TASK_STOPPED_MESSAGE_TEMPLATE, graphIdStr, "用户终止"))
								.build());
					sink.tryEmitComplete();
					return;
				}
			}

			// 任务正常完成
			logger.info("Stream processing completed.");
			sink.tryEmitComplete();
			// 从任务Map中移出
			graphTaskFutureMap.remove(graphId);
		});
		// 存放到Map中
		Future<?> oldFuture = graphTaskFutureMap.put(graphId, future);
		Optional.ofNullable(oldFuture).ifPresent((f) -> {
			if (!f.isDone()) {
				logger.warn("A task with the same GraphId {} is still running!", graphId);
			}
		});
	}

	/**
	 * 终止运行中的图
	 * @param graphId graphId
	 * @return 是否成功
	 *
	 * 使用场景：
	 * 用户调用点击“停止”按钮
	 * 前端调用/stop接口，传入graphId
	 * 后端从Map中找到对应的Future
	 * 调用future.cancel()中断任务
	 */
	public boolean stopGraph(GraphId graphId) {
		Future<?> future = this.graphTaskFutureMap.remove(graphId);
		if (future == null) {
			return false; // 任务不存在
		}
		if (future.isDone()) {
			return true; // 任务已完成
		}
		return future.cancel(true); // 取消任务！
	}

	private String buildLLMNodeContent(String nodeName, GraphId graphId, StreamingOutput streamingOutput,
			NodeOutput output) {
		StreamNodePrefixEnum prefixEnum = StreamNodePrefixEnum.match(nodeName);
		if (prefixEnum == null) {
			return "";
		}
		String stepTitle = (String) output.state().value(nodeName + "_step_title").orElse("");
		String finishReason = Optional.ofNullable(streamingOutput.chatResponse())
			.map(ChatResponse::getResult)
			.map(Generation::getMetadata)
			.map(ChatGenerationMetadata::getFinishReason)
			.orElse("");

		String textContent = streamingOutput.chunk() == null
				? streamingOutput.chatResponse().getResult().getOutput().getText() : streamingOutput.chunk();
		Map<String, Serializable> response = Map.of(nodeName, textContent, "step_title", stepTitle, "visible",
				prefixEnum.isVisible(), "finishReason", finishReason, "graphId", graphId);

		return this.safeObjectToJson(response);
	}

	private record NodeResponse(String nodeName, GraphId graphId, String displayTitle, Object content,
			Object siteInformation) {
	}

	private String buildNormalNodeContent(GraphId graphId, String nodeName, NodeOutput output) {
		NodeNameEnum nodeEnum = NodeNameEnum.fromNodeName(nodeName);
		if (nodeEnum == null) {
			return "";
		}
		Object content;
		// 不同节点给前端的内容不一样
		content = switch (nodeEnum) {
			case START -> {
				String query = output.state().data().get("query").toString();
				yield Map.of("query", query);
			}
			case COORDINATOR -> output.state().data().get("deep_research");
			case REWRITE_MULTI_QUERY, HUMAN_FEEDBACK, END -> output.state().data();
			case PLANNER -> output.state().data().get("planner_content");
			case RESEARCH_TEAM -> {
				String researchTeamContent = (String) output.state().data().get("research_team_content");
				yield StringUtils.equals(researchTeamContent, NodeNameEnum.REPORTER.nodeName());
			}
			case REPORTER -> output.state().data().get("final_report");
			default -> "";
		};
		Object site_information = output.state().value("site_information").orElse("");
		String displayTitle = nodeEnum.displayTitle();
		if (StringUtils.isEmpty(displayTitle)
				|| (Objects.equals(content, "") && Objects.equals(site_information, ""))) {
			return "";
		}
		NodeResponse response = new NodeResponse(nodeName, graphId, displayTitle, content, site_information);
		try {
			return OBJECT_MAPPER.writeValueAsString(response);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to serialize NodeResponse", e);
		}
	}

}
