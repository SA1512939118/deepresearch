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

package com.alibaba.cloud.ai.example.deepresearch.config;

import com.alibaba.cloud.ai.example.deepresearch.config.rag.RagProperties;
import com.alibaba.cloud.ai.example.deepresearch.dispatcher.*;
import com.alibaba.cloud.ai.example.deepresearch.memory.ShortTermMemoryRepository;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.ParallelEnum;

import com.alibaba.cloud.ai.example.deepresearch.node.*;
import com.alibaba.cloud.ai.example.deepresearch.service.RagNodeService;
import com.alibaba.cloud.ai.example.deepresearch.service.SessionContextService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.QuestionClassifierService;
import com.alibaba.cloud.ai.example.deepresearch.service.ReportService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SearchPlatformSelectionService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SmartAgentDispatcherService;

import com.alibaba.cloud.ai.example.deepresearch.serializer.DeepResearchStateSerializer;
import com.alibaba.cloud.ai.example.deepresearch.service.InfoCheckService;
import com.alibaba.cloud.ai.example.deepresearch.service.SearchFilterService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.ToolCallingSearchService;
import com.alibaba.cloud.ai.example.deepresearch.util.ReflectionProcessor;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.toolcalling.jinacrawler.JinaCrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.example.deepresearch.service.McpProviderFactory;

/**
 * DeepResearchConfiguration - 深度研究系统的核心配置类
 *
 * 【类的作用】:
 * 1. 构建和配置深度研究的状态图(StateGraph),定义完整的工作流
 * 2. 注入和管理所有AI代理(Agent)和依赖服务
 * 3. 定义节点之间的流转关系和条件边
 * 4. 配置并行节点的数量和执行策略
 * 5. 管理状态的更新策略(KeyStrategy)
 *
 * 【设计理念】:
 * 1. 图编排模式: 使用StateGraph编排复杂的多阶段工作流
 * 2. 多智能体协作: 不同Agent扮演不同角色(协调员、研究员、程序员等)
 * 3. 并行处理: 支持并行执行多个Researcher和Coder节点以提高效率
 * 4. 状态管理: 使用KeyStrategy精细控制每个状态字段的更新策略
 * 5. 条件流转: 通过Dispatcher实现智能路由,根据状态动态决定下一个节点
 * 6. 模块化设计: 每个节点独立封装,易于扩展和维护
 * 7. 异步执行: 使用node_async和edge_async实现异步节点和边,提高吞吐量
 *
 * 【在项目中的地位】:
 * - 这是整个深度研究系统的"大脑"和"指挥中心"
 * - 所有研究流程的起点和核心编排器
 * - 连接各个组件的桥梁,确保系统协同工作
 *
 * 【工作流程概览】:
 * START -> 短期记忆 -> 协调器 -> 查询重写 -> 背景调查 -> 计划制定 -> 信息收集 -> 研究团队 -> 并行执行 -> (研究员/程序员并行) -> 研究团队 -> 知识库决策 -> 报告生成 -> END
 *
 * @author yingzi
 * @since 2025/5/17 17:10
 */

/**
 * @EnableConfigurationProperties : 启用配置属性绑定,将配置文件中的属性映射到Java对象
 * 
 * 配置的属性类包括:
 * - DeepResearchProperties: 深度研究主配置(并行节点数量、最大迭代次数、搜索列表等)
 * - PythonCoderProperties: Python编程节点配置
 * - McpAssignNodeProperties: MCP(Model Context Protocol)节点配置
 * - RagProperties: RAG检索增强配置(向量库、知识库等)
 * - ReflectionProperties: 反思机制配置(最大尝试次数、是否启用)
 * - SmartAgentProperties: 智能代理配置
 * - ShortTermMemoryProperties: 短期记忆配置(记忆窗口大小、存储策略等)
 */
@Configuration
@EnableConfigurationProperties({ DeepResearchProperties.class, PythonCoderProperties.class,
		McpAssignNodeProperties.class, RagProperties.class, ReflectionProperties.class, SmartAgentProperties.class,
		ShortTermMemoryProperties.class })
public class DeepResearchConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(DeepResearchConfiguration.class);

	// ==================== AI代理注入 ====================
	// 这些Agent分别扮演不同角色,使用不同的Prompt和模型配置

	@Autowired
	private ChatClient coderAgent; // 程序员代理:负责代码编写和Python脚本生成

	@Autowired
	private ChatClient researchAgent; // 研究员代理:负责信息检索和内容研究

	@Autowired
	private ChatClient reporterAgent; // 报告员代理:负责生成最终研究报告

	@Autowired
	private ChatClient backgroundAgent; // 背景调查代理:负责初步信息收集和背景调研

	@Autowired
	private ChatClient coordinatorAgent; // 协调员代理:负责整体流程协调和任务分配

	@Autowired
	private ChatClient plannerAgent; // 计划员代理:负责制定研究计划和任务分解

	@Autowired
	private ChatClient reflectionAgent; // 反思代理:负责对结果进行反思和改进

	@Autowired
	private ChatClient shortMemoryAgent; // 短期记忆代理:负责提取和更新用户短期记忆

	@Autowired
	private ChatClient.Builder rewriteAndMultiQueryChatClientBuilder; // 查询重写和多查询生成器:负责优化用户查询

	// ==================== 配置属性注入 ====================

	@Autowired
	private DeepResearchProperties deepResearchProperties; // 深度研究主配置

	@Autowired
	private ReflectionProperties reflectionProperties; // 反思机制配置

	@Autowired
	private ShortTermMemoryProperties shortTermMemoryProperties; // 短期记忆配置

	@Autowired
	private ShortTermMemoryRepository shortTermMemoryRepository; // 短期记忆存储库

	@Autowired(required = false)
	private MessageWindowChatMemory messageWindowChatMemory; // 消息窗口记忆(可选)

	@Autowired(required = false)
	private JinaCrawlerService jinaCrawlerService; // Jina爬虫服务(可选),用于网页抓取

	@Autowired
	private RagProperties ragProperties; // RAG配置

	@Autowired
	private ReportService reportService; // 报告服务

	@Autowired
	private SessionContextService sessionContextService; // 会话上下文服务

	@Autowired(required = false)
	private McpProviderFactory mcpProviderFactory; // MCP提供者工厂(可选)

	@Autowired
	private InfoCheckService infoCheckService; // 信息检查服务

	@Autowired
	private SearchFilterService searchFilterService; // 搜索过滤服务

	// ==================== 多智能体相关服务注入 ====================

	// 可选的工具调用服务（智能平台用）
	@Autowired(required = false)
	private ToolCallingSearchService toolCallingSearchService; // 工具调用搜索服务(可选)

	@Autowired(required = false)
	private QuestionClassifierService questionClassifierService; // 问题分类服务(可选)

	@Autowired(required = false)
	private SearchPlatformSelectionService searchPlatformSelectionService; // 搜索平台选择服务(可选)

	@Autowired(required = false)
	private SmartAgentDispatcherService smartAgentDispatcher; // 智能代理分发服务(可选)

	@Autowired
	private SmartAgentProperties smartAgentProperties; // 智能代理配置

	@Autowired
	private RagNodeService ragNodeService; // RAG节点服务

	/**
	 * 创建反思处理器Bean
	 * 
	 * 【作用】: 如果启用了反思机制,则创建ReflectionProcessor实例
	 * 【设计理念】: 反思机制允许系统对研究结果进行自我评估和改进
	 * 【使用场景】: 在ResearcherNode和CoderNode中,当完成研究或编程后,进行反思以提升质量
	 * 
	 * @return ReflectionProcessor实例,如果反思机制未启用则返回null
	 */
	@Bean
	public ReflectionProcessor reflectionProcessor() {
		if (!reflectionProperties.isEnabled()) {
			return null; // 如果反思机制未启用,返回null
		}
		// 使用专门的反思代理创建处理器,并传入最大尝试次数
		return new ReflectionProcessor(reflectionAgent, reflectionProperties.getMaxAttempts());
	}

	/**
	 * 创建深度研究状态图Bean - 这是整个系统的核心
	 * 
	 * 【作用】: 构建完整的状态图,定义所有节点、边和流转规则
	 * 【设计理念】: 
	 * 1. 使用StateGraph编排复杂的多阶段工作流
	 * 2. 通过KeyStrategy精细控制状态更新策略
	 * 3. 使用条件边实现智能路由
	 * 4. 支持并行执行以提高效率
	 * 【在项目中的地位】: 这是深度研究系统的"大脑",所有研究流程都由这个状态图控制
	 * 
	 * @param researchAgent 研究员代理
	 * @return 配置好的StateGraph实例
	 * @throws GraphStateException 图状态异常
	 */
	@Bean
	public StateGraph deepResearch(ChatClient researchAgent) throws GraphStateException {

		// ==================== 状态策略工厂配置 ====================
		// KeyStrategyFactory定义了每个状态字段如何更新
		// ReplaceStrategy表示直接替换旧值,这是最常用的策略
		
		KeyStrategyFactory keyStrategyFactory = () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			
			// 条件边控制字段: 这些字段控制下一个节点的跳转
			keyStrategyHashMap.put("short_user_role_next_node", new ReplaceStrategy()); // 短期记忆节点的下一个节点
			keyStrategyHashMap.put("coordinator_next_node", new ReplaceStrategy()); // 协调员节点的下一个节点
			keyStrategyHashMap.put("rewrite_multi_query_next_node", new ReplaceStrategy()); // 查询重写节点的下一个节点
			keyStrategyHashMap.put("background_investigation_next_node", new ReplaceStrategy()); // 背景调查节点的下一个节点
			keyStrategyHashMap.put("planner_next_node", new ReplaceStrategy()); // 计划员节点的下一个节点
			keyStrategyHashMap.put("information_next_node", new ReplaceStrategy()); // 信息收集节点的下一个节点
			keyStrategyHashMap.put("human_next_node", new ReplaceStrategy()); // 人工反馈节点的下一个节点
			keyStrategyHashMap.put("research_team_next_node", new ReplaceStrategy()); // 研究团队节点的下一个节点
			
			// 用户输入字段: 这些字段来自用户输入,使用ReplaceStrategy直接更新
			keyStrategyHashMap.put("query", new ReplaceStrategy()); // 用户查询
			keyStrategyHashMap.put("optimize_queries", new ReplaceStrategy()); // 优化后的查询列表
			keyStrategyHashMap.put("thread_id", new ReplaceStrategy()); // 线程ID
			keyStrategyHashMap.put("enable_deepresearch", new ReplaceStrategy()); // 是否启用深度研究
			keyStrategyHashMap.put("auto_accepted_plan", new ReplaceStrategy()); // 是否自动接受计划
			keyStrategyHashMap.put("plan_max_iterations", new ReplaceStrategy()); // 计划最大迭代次数
			keyStrategyHashMap.put("max_step_num", new ReplaceStrategy()); // 最大步数
			keyStrategyHashMap.put("mcp_settings", new ReplaceStrategy()); // MCP设置
			keyStrategyHashMap.put("optimize_query_num", new ReplaceStrategy()); // 优化查询数量
			keyStrategyHashMap.put("user_upload_file", new ReplaceStrategy()); // 用户上传文件
			keyStrategyHashMap.put("session_id", new ReplaceStrategy()); // 会话ID

			keyStrategyHashMap.put("feedback", new ReplaceStrategy()); // 用户反馈
			keyStrategyHashMap.put("feedback_content", new ReplaceStrategy()); // 反馈内容

			// 专业知识库决策相关字段
			keyStrategyHashMap.put("use_professional_kb", new ReplaceStrategy()); // 是否使用专业知识库
			keyStrategyHashMap.put("selected_knowledge_bases", new ReplaceStrategy()); // 选中的知识库列表

			// 节点输出字段: 这些字段是各个节点的输出结果
			keyStrategyHashMap.put("background_investigation_results", new ReplaceStrategy()); // 背景调查结果
			keyStrategyHashMap.put("site_information", new ReplaceStrategy()); // 站点信息
			keyStrategyHashMap.put("output", new ReplaceStrategy()); // 通用输出
			keyStrategyHashMap.put("plan_iterations", new ReplaceStrategy()); // 计划迭代次数
			keyStrategyHashMap.put("current_plan", new ReplaceStrategy()); // 当前计划
			keyStrategyHashMap.put("observations", new ReplaceStrategy()); // 观察结果
			keyStrategyHashMap.put("final_report", new ReplaceStrategy()); // 最终报告
			keyStrategyHashMap.put("planner_content", new ReplaceStrategy()); // 计划员内容

			// 并行节点输出字段: 为每个并行节点创建独立的状态字段
			// 这样可以收集所有并行节点的输出,避免互相覆盖
			for (int i = 0; i < deepResearchProperties.getParallelNodeCount()
				.get(ParallelEnum.RESEARCHER.getValue()); i++) {
				keyStrategyHashMap.put(ParallelEnum.RESEARCHER.getValue() + "_content_" + i, new ReplaceStrategy());
			}
			for (int i = 0; i < deepResearchProperties.getParallelNodeCount().get(ParallelEnum.CODER.getValue()); i++) {
				keyStrategyHashMap.put(ParallelEnum.CODER.getValue() + "_content_" + i, new ReplaceStrategy());
			}

			return keyStrategyHashMap;
		};

		// ==================== 状态图构建 ====================
		// 创建StateGraph实例,配置名称、状态策略和序列化器
		// DeepResearchStateSerializer用于序列化和反序列化状态,支持持久化
		
		StateGraph stateGraph = new StateGraph("deep research", keyStrategyFactory,
				new DeepResearchStateSerializer(OverAllState::new))
			
			// ==================== 节点添加 ====================
			// 每个节点代表一个处理步骤,使用node_async实现异步执行
			
			// 节点1: 短期用户角色记忆节点
			// 作用: 提取和更新用户的短期记忆,实现个性化服务
			// 依赖: 短期记忆代理、短期记忆配置、短期记忆存储库
			.addNode("short_user_role_memory",
					node_async(new ShortUserRoleMemoryNode(shortMemoryAgent, shortTermMemoryProperties,
							shortTermMemoryRepository)))
			
			// 节点2: 协调员节点
			// 作用: 协调整个研究流程,决定是否继续研究或直接结束
			// 依赖: 协调员代理、会话上下文服务、消息窗口记忆、短期记忆配置
			.addNode("coordinator",
					node_async(new CoordinatorNode(coordinatorAgent, sessionContextService, messageWindowChatMemory,
							shortTermMemoryProperties)))
			
			// 节点3: 查询重写和多查询节点
			// 作用: 将用户查询重写为更优化的形式,并生成多个相关查询以提高检索效果
			// 依赖: 查询重写代理构建器、短期记忆存储库、短期记忆配置
			.addNode("rewrite_multi_query",
					node_async(new RewriteAndMultiQueryNode(rewriteAndMultiQueryChatClientBuilder,
							shortTermMemoryRepository, shortTermMemoryProperties)))
			
			// 节点4: 背景调查节点
			// 作用: 进行初步的背景调查和信息收集,为后续研究提供基础
			// 依赖: Jina爬虫服务、信息检查服务、搜索过滤服务、问题分类服务、搜索平台选择服务、智能代理配置、背景调查代理、会话上下文服务、工具调用搜索服务
			.addNode("background_investigator",
					node_async(new BackgroundInvestigationNode(jinaCrawlerService, infoCheckService,
							searchFilterService, questionClassifierService, searchPlatformSelectionService,
							smartAgentProperties, backgroundAgent, sessionContextService, toolCallingSearchService)))
			
			// 节点5: 用户文件RAG节点
			// 作用: 从用户上传的文件中进行RAG检索,获取相关信息
			// 依赖: RAG节点服务
			.addNode("user_file_rag", ragNodeService.createUserFileRagNode())
			
			// 节点6: 计划员节点
			// 作用: 制定详细的研究计划,将复杂任务分解为可执行的步骤
			// 依赖: 计划员代理
			.addNode("planner", node_async((new PlannerNode(plannerAgent))))
			
			// 节点7: 专业知识库决策节点
			// 作用: 决定是否需要使用专业知识库,以及选择哪些知识库
			// 依赖: 研究员代理、RAG配置
			.addNode("professional_kb_decision",
					node_async(new ProfessionalKbDecisionNode(researchAgent, ragProperties)))
			
			// 节点8: 专业知识库RAG节点
			// 作用: 从专业知识库中进行RAG检索,获取专业领域信息
			// 依赖: RAG节点服务
			.addNode("professional_kb_rag", ragNodeService.createProfessionalKbRagNode())
			
			// 节点9: 信息收集节点
			// 作用: 收集和整理研究过程中的信息
			// 依赖: 无
			.addNode("information", node_async((new InformationNode())))
			
			// 节点10: 人工反馈节点
			// 作用: 等待用户反馈,允许用户介入研究流程
			// 依赖: 无
			.addNode("human_feedback", node_async(new HumanFeedbackNode()))
			
			// 节点11: 研究团队节点
			// 作用: 协调研究团队的工作,决定下一步行动
			// 依赖: 无
			.addNode("research_team", node_async(new ResearchTeamNode()))
			
			// 节点12: 并行执行器节点
			// 作用: 触发并行执行,同时启动多个研究员和程序员节点
			// 依赖: 深度研究配置(包含并行节点数量)
			.addNode("parallel_executor", node_async(new ParallelExecutorNode(deepResearchProperties)))
			
			// 节点13: 报告员节点
			// 作用: 生成最终的研究报告,整合所有研究结果
			// 依赖: 报告员代理、报告服务、会话上下文服务、消息窗口记忆、短期记忆配置
			.addNode("reporter", node_async(new ReporterNode(reporterAgent, reportService, sessionContextService,
					messageWindowChatMemory, shortTermMemoryProperties)));

		// ==================== 配置并行节点 ====================
		// 动态添加多个研究员和程序员节点,实现并行处理
		configureParallelNodes(stateGraph);

		// ==================== 边添加 ====================
		// 定义节点之间的流转关系,包括普通边和条件边
		
		// 从START到短期记忆节点: 流程的起点
		stateGraph.addEdge(START, "short_user_role_memory")
			
			// 从短期记忆到协调员或END: 条件边,根据short_user_role_next_node决定
			.addConditionalEdges("short_user_role_memory", edge_async(new ShortUserRoleMemoryDispatcher()),
					Map.of("coordinator", "coordinator", END, END))
			
			// 从协调员到查询重写或END: 条件边,根据coordinator_next_node决定
			.addConditionalEdges("coordinator", edge_async(new CoordinatorDispatcher()),
					Map.of("rewrite_multi_query", "rewrite_multi_query", END, END))
			
			// 从查询重写到背景调查/用户文件RAG/END: 条件边,根据rewrite_multi_query_next_node决定
			.addConditionalEdges("rewrite_multi_query", edge_async(new RewriteAndMultiQueryDispatcher()),
					Map.of("background_investigator", "background_investigator", "user_file_rag", "user_file_rag", END,
							END))
			
			// 从背景调查到报告员/计划员/END: 条件边,根据background_investigation_next_node决定
			.addConditionalEdges("background_investigator", edge_async(new BackgroundInvestigationDispatcher()),
					Map.of("reporter", "reporter", "planner", "planner", END, END))
			
			// 从用户文件RAG到背景调查: 普通边,无条件执行
			.addEdge("user_file_rag", "background_investigator")
			
			// 从计划员到信息收集: 普通边,无条件执行
			.addEdge("planner", "information")
			
			// 从信息收集到报告员/人工反馈/计划员/研究团队/END: 条件边,根据information_next_node决定
			.addConditionalEdges("information", edge_async(new InformationDispatcher()),
					Map.of("reporter", "reporter", "human_feedback", "human_feedback", "planner", "planner",
							"research_team", "research_team", END, END))
			
			// 从人工反馈到计划员/研究团队/END: 条件边,根据human_next_node决定
			.addConditionalEdges("human_feedback", edge_async(new HumanFeedbackDispatcher()),
					Map.of("planner", "planner", "research_team", "research_team", END, END))
			
			// 从研究团队到专业知识库决策/并行执行器/END: 条件边,根据research_team_next_node决定
			.addConditionalEdges("research_team", edge_async(new ResearchTeamDispatcher()),
					Map.of("professional_kb_decision", "professional_kb_decision", "parallel_executor",
							"parallel_executor", END, END))
			
			// 从专业知识库决策到专业知识库RAG/报告员/END: 条件边,根据use_professional_kb决定
			.addConditionalEdges("professional_kb_decision", edge_async(new ProfessionalKbDispatcher()),
					Map.of("professional_kb_rag", "professional_kb_rag", "reporter", "reporter", END, END))
			
			// 从专业知识库RAG到报告员: 普通边,无条件执行
			.addEdge("professional_kb_rag", "reporter")
			
			// 从报告员到END: 流程的终点
			.addEdge("reporter", END);

		// ==================== 生成流程图 ====================
		// 生成PlantUML格式的流程图,便于可视化理解工作流
		// 这对调试和文档非常有用
		GraphRepresentation graphRepresentation = stateGraph.getGraph(GraphRepresentation.Type.PLANTUML,
				"workflow graph");

		logger.info("\n\n");
		logger.info(graphRepresentation.content()); // 将流程图输出到日志
		logger.info("\n\n");

		return stateGraph;
	}

	/**
	 * 配置并行节点
	 * 
	 * 【作用】: 动态添加多个研究员和程序员节点到状态图中
	 * 【设计理念】: 并行处理可以显著提高研究效率,多个研究员可以同时研究不同方面
	 * 【实现方式】: 根据配置文件中的并行节点数量,动态创建对应数量的节点
	 * 
	 * @param stateGraph 状态图实例
	 * @throws GraphStateException 图状态异常
	 */
	private void configureParallelNodes(StateGraph stateGraph) throws GraphStateException {
		addResearcherNodes(stateGraph); // 添加研究员节点

		addCoderNodes(stateGraph); // 添加程序员节点
	}

	/**
	 * 添加研究员节点
	 * 
	 * 【作用】: 根据配置添加多个研究员节点,每个节点可以独立进行研究
	 * 【设计理念】: 
	 * 1. 每个研究员节点有独立的ID(researcher_0, researcher_1, ...)
	 * 2. 每个节点可以访问反思处理器,对研究结果进行反思
	 * 3. 支持MCP(Model Context Protocol)扩展
	 * 4. 支持智能代理分发
	 * 【在项目中的地位】: 研究员节点是信息收集的核心,多个研究员并行工作可以提高研究广度和深度
	 * 
	 * @param stateGraph 状态图实例
	 * @throws GraphStateException 图状态异常
	 */
	private void addResearcherNodes(StateGraph stateGraph) throws GraphStateException {
		ReflectionProcessor reflectionProcessor = reflectionProcessor(); // 获取反思处理器
		for (int i = 0; i < deepResearchProperties.getParallelNodeCount()
			.get(ParallelEnum.RESEARCHER.getValue()); i++) {
			String nodeId = "researcher_" + i; // 节点ID: researcher_0, researcher_1, ...
			stateGraph.addNode(nodeId,
					node_async(new ResearcherNode(researchAgent, String.valueOf(i), reflectionProcessor,
							mcpProviderFactory, searchFilterService, smartAgentDispatcher, smartAgentProperties,
							jinaCrawlerService)));
			// 从并行执行器到研究员节点,再从研究员节点回到研究团队
			stateGraph.addEdge("parallel_executor", nodeId).addEdge(nodeId, "research_team");
		}
	}

	/**
	 * 添加程序员节点
	 * 
	 * 【作用】: 根据配置添加多个程序员节点,每个节点可以独立编写代码
	 * 【设计理念】: 
	 * 1. 每个程序员节点有独立的ID(coder_0, coder_1, ...)
	 * 2. 每个节点可以访问反思处理器,对代码进行反思和优化
	 * 3. 支持MCP(Model Context Protocol)扩展
	 * 【在项目中的地位】: 程序员节点负责生成Python脚本和代码,支持数据分析和可视化
	 * 
	 * @param stateGraph 状态图实例
	 * @throws GraphStateException 图状态异常
	 */
	private void addCoderNodes(StateGraph stateGraph) throws GraphStateException {
		ReflectionProcessor reflectionProcessor = reflectionProcessor(); // 获取反思处理器
		for (int i = 0; i < deepResearchProperties.getParallelNodeCount().get(ParallelEnum.CODER.getValue()); i++) {
			String nodeId = "coder_" + i; // 节点ID: coder_0, coder_1, ...
			stateGraph.addNode(nodeId,
					node_async(new CoderNode(coderAgent, String.valueOf(i), reflectionProcessor, mcpProviderFactory)));
			// 从并行执行器到程序员节点,再从程序员节点回到研究团队
			stateGraph.addEdge("parallel_executor", nodeId).addEdge(nodeId, "research_team");
		}
	}

}
