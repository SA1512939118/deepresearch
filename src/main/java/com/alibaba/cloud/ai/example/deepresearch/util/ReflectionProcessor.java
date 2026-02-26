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

package com.alibaba.cloud.ai.example.deepresearch.util;

import com.alibaba.cloud.ai.example.deepresearch.model.dto.Plan;
import com.alibaba.cloud.ai.example.deepresearch.model.dto.ReflectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

/**
 * ReflectionProcessor - 反思处理器类
 *
 * 【类的作用】:
 * 1. 对研究任务(Researcher)和编程任务(Coder)的执行结果进行质量评估
 * 2. 使用专门的反思AI代理(ReflectionAgent)对任务完成质量进行智能评判
 * 3. 支持多次反思尝试,如果质量不达标则重新执行任务
 * 4. 记录每次反思的历史,包括评估结果、反馈意见和执行结果
 * 5. 防止无限循环,设置最大反思次数限制
 *
 * 【设计理念】:
 * 1. 自我反思机制: 模拟人类的自我评估和改进过程
 * 2. 质量保证: 通过AI评估确保研究或编程结果的质量
 * 3. 迭代优化: 允许多次尝试,逐步提升结果质量
 * 4. 状态机模式: 通过状态流转控制反思和重新处理的流程
 * 5. 容错设计: 评估失败时默认通过,避免阻塞流程
 * 6. 结构化输出: 使用BeanOutputConverter解析AI返回的JSON格式评估结果
 *
 * 【在项目中的地位】:
 * - 这是深度研究系统的"质量检查员"和"自我改进引擎"
 * - 确保ResearcherNode和CoderNode的输出质量
 * - 提升整个研究系统的可靠性和专业性
 * - 实现了AI系统的自我评估和优化能力
 *
 * 【工作流程】:
 * 1. 任务执行完成后,标记状态为"等待反思"(waiting_reflecting)
 * 2. ReflectionProcessor评估任务质量
 * 3. 如果质量达标 → 标记为"已完成"(completed),继续下一步
 * 4. 如果质量不达标 → 标记为"等待重新处理"(waiting_processing),增加尝试次数
 * 5. 下次执行时,根据反馈重新执行任务
 * 6. 如果达到最大尝试次数 → 强制通过,标记为"已完成"
 *
 * 【状态流转图】:
 * processing → waiting_reflecting → [评估] → completed (通过)
 *                                    ↓ (不通过)
 *                            waiting_processing → processing (重新执行)
 *
 * @author sixiyida
 * @since 2025/7/10
 */
public class ReflectionProcessor {

	private static final Logger logger = LoggerFactory.getLogger(ReflectionProcessor.class);

	// ==================== 核心依赖 ====================

	/**
	 * 反思AI代理
	 * 
	 * 【作用】: 专门用于评估任务质量的AI代理
	 * 【特点】: 使用专门的Prompt和模型配置,专注于质量评估
	 * 【配置】: 在DeepResearchConfiguration中通过reflectionAgent注入
	 */
	private final ChatClient reflectionAgent;

	/**
	 * 最大反思尝试次数
	 * 
	 * 【作用】: 防止无限循环,限制反思和重新执行的次数
	 * 【默认值】: 由ReflectionProperties配置,通常为2-3次
	 * 【设计理念】: 平衡质量保证和执行效率
	 */
	private final int maxReflectionAttempts;

	/**
	 * Bean输出转换器
	 * 
	 * 【作用】: 将AI返回的JSON格式评估结果转换为ReflectionResult对象
	 * 【优势】: 类型安全,自动解析,支持复杂嵌套结构
	 * 【使用场景】: 解析reflectionAgent返回的评估结果
	 */
	private final BeanOutputConverter<ReflectionResult> converter;

	// ==================== 构造函数 ====================

	/**
	 * 反思处理器构造函数
	 * 
	 * 【作用】: 初始化反思处理器,注入必要的依赖
	 * 【调用时机】: 在DeepResearchConfiguration的reflectionProcessor()方法中创建
	 * 【设计理念】: 依赖注入,便于测试和配置管理
	 * 
	 * @param reflectionAgent 反思AI代理,用于评估任务质量
	 * @param maxReflectionAttempts 最大反思尝试次数,防止无限循环
	 */
	public ReflectionProcessor(ChatClient reflectionAgent, int maxReflectionAttempts) {
		this.reflectionAgent = reflectionAgent;
		this.maxReflectionAttempts = maxReflectionAttempts;
		// 初始化转换器,用于解析AI返回的JSON格式评估结果
		this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<ReflectionResult>() {
		});
	}

	// ==================== 公共方法 ====================

	/**
	 * 处理反思逻辑 - 这是核心入口方法
	 * 
	 * 【作用】: 根据步骤的执行状态,决定是进行反思评估、重新处理还是继续执行
	 * 【设计理念】: 状态机模式,根据当前状态决定下一步行动
	 * 【调用时机】: 在ResearcherNode和CoderNode的apply()方法中调用
	 * 【在项目中的地位】: 这是反思机制的主入口,控制整个反思流程
	 * 
	 * 【状态处理逻辑】:
	 * 1. waiting_reflecting状态 → 执行反思评估
	 * 2. waiting_processing状态 → 准备重新处理
	 * 3. 其他状态 → 继续正常执行
	 * 
	 * @param step 计划步骤对象,包含任务信息、执行状态、反思历史等
	 * @param nodeName 节点名称,如"researcher_0"或"coder_1"
	 * @param nodeType 节点类型,用于构建评估Prompt("researcher"或"coder")
	 * @return ReflectionHandleResult 包含是否继续执行的信息
	 */
	public ReflectionHandleResult handleReflection(Plan.Step step, String nodeName, String nodeType) {
		// 获取当前步骤的执行状态
		String currentStatus = step.getExecutionStatus();

		// 状态1: 等待反思评估
		// 当任务首次执行完成,或者重新执行完成后,会进入这个状态
		if (currentStatus != null && currentStatus.startsWith(StateUtil.EXECUTION_STATUS_WAITING_REFLECTING)) {
			// 执行反思评估,判断质量是否达标
			return performReflection(step, nodeName, nodeType);
		}

		// 状态2: 等待重新处理
		// 当反思评估发现质量不达标,会进入这个状态
		if (currentStatus != null && currentStatus.startsWith(StateUtil.EXECUTION_STATUS_WAITING_PROCESSING)) {
			// 将状态更新为"正在处理",准备重新执行任务
			step.setExecutionStatus(StateUtil.EXECUTION_STATUS_PROCESSING_PREFIX + nodeName);
			// 清空之前的执行结果,为重新执行做准备
			step.setExecutionRes("");
			logger.info("Step {} is ready for reprocessing", step.getTitle());
			// 返回继续处理,让节点重新执行任务
			return ReflectionHandleResult.continueProcessing();
		}

		// 状态3: 其他状态(如processing、completed等)
		// 不需要反思处理,继续正常执行
		return ReflectionHandleResult.continueProcessing();
	}

	// ==================== 私有方法 ====================

	/**
	 * 执行反思评估
	 * 
	 * 【作用】: 使用AI代理评估任务完成质量,决定是否需要重新执行
	 * 【设计理念】: 
	 * 1. 智能评估: 使用AI判断质量,而非简单的规则
	 * 2. 迭代优化: 允许多次尝试,逐步提升质量
	 * 3. 防止无限循环: 设置最大尝试次数限制
	 * 4. 容错设计: 评估失败时默认通过,避免阻塞流程
	 * 【在项目中的地位】: 这是反思机制的核心,决定任务是否需要重新执行
	 * 
	 * 【执行流程】:
	 * 1. 检查是否达到最大尝试次数
	 * 2. 如果达到 → 强制通过,标记为已完成
	 * 3. 如果未达到 → 调用AI评估质量
	 * 4. 如果质量达标 → 标记为已完成
	 * 5. 如果质量不达标 → 标记为等待重新处理,增加尝试次数
	 * 
	 * @param step 计划步骤对象
	 * @param nodeName 节点名称
	 * @param nodeType 节点类型
	 * @return ReflectionHandleResult 包含是否继续执行的信息
	 */
	private ReflectionHandleResult performReflection(Plan.Step step, String nodeName, String nodeType) {
		try {
			// 获取当前已进行的反思尝试次数
			int attemptCount = getReflectionAttemptCount(step);
			
			// 检查是否达到最大尝试次数
			if (attemptCount >= maxReflectionAttempts) {
				logger.warn("Step {} has reached maximum reflection attempts {}, forcing pass", step.getTitle(),
						maxReflectionAttempts);
				// 强制通过,标记为已完成
				step.setExecutionStatus(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName);
				// 返回跳过处理,不再重新执行
				return ReflectionHandleResult.skipProcessing();
			}

			// 使用AI评估步骤质量
			boolean qualityGood = evaluateStepQuality(step, nodeType);

			if (qualityGood) {
				// 质量达标,标记为已完成
				step.setExecutionStatus(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName);
				logger.info("Step {} reflection passed, quality is acceptable", step.getTitle());
				// 返回跳过处理,不再重新执行
				return ReflectionHandleResult.skipProcessing();
			}
			else {
				// 质量不达标,增加尝试次数
				incrementReflectionAttemptCount(step);
				// 标记为等待重新处理
				step.setExecutionStatus(StateUtil.EXECUTION_STATUS_WAITING_PROCESSING + nodeName);
				logger.info("Step {} reflection failed, marked for reprocessing (attempt {})", step.getTitle(),
						attemptCount + 1);
				// 返回跳过处理,下次会进入waiting_processing状态
				return ReflectionHandleResult.skipProcessing();
			}

		}
		catch (Exception e) {
			// 容错处理: 评估失败时默认通过,避免阻塞流程
			logger.error("Reflection process failed, defaulting to pass: {}", e.getMessage());
			// 标记为已完成
			step.setExecutionStatus(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName);
			// 返回跳过处理
			return ReflectionHandleResult.skipProcessing();
		}
	}

	/**
	 * 评估步骤质量
	 * 
	 * 【作用】: 使用AI代理评估任务完成质量,返回是否达标
	 * 【设计理念】: 
	 * 1. 结构化Prompt: 构建清晰的评估Prompt,包含任务信息和执行结果
	 * 2. JSON格式输出: 要求AI返回JSON格式的评估结果,便于解析
	 * 3. 记录历史: 将评估结果保存到步骤的反思历史中
	 * 4. 容错设计: 评估失败时默认通过,避免阻塞流程
	 * 【在项目中的地位】: 这是质量评估的核心实现
	 * 
	 * 【评估Prompt结构】:
	 * - 任务类型描述
	 * - 任务标题
	 * - 任务描述
	 * - 执行结果
	 * 
	 * 【AI返回格式】:
	 * {
	 *   "passed": true/false,
	 *   "feedback": "评估反馈意见",
	 *   "executionResult": "原始执行结果"
	 * }
	 * 
	 * @param step 计划步骤对象
	 * @param nodeType 节点类型("researcher"或"coder")
	 * @return true表示质量达标,false表示需要重新执行
	 */
	private boolean evaluateStepQuality(Plan.Step step, String nodeType) {
		// 构建评估Prompt,包含任务信息和执行结果
		String evaluationPrompt = buildEvaluationPrompt(step, nodeType);

		try {
			// 调用反思AI代理进行质量评估
			// converter.getFormat()会生成JSON格式说明,告诉AI如何返回结果
			var response = reflectionAgent.prompt(converter.getFormat()).user(evaluationPrompt).call().chatResponse();

			// 获取AI返回的文本结果
			String responseText = response.getResult().getOutput().getText().trim();
			// 将JSON文本转换为ReflectionResult对象
			ReflectionResult reflectionResult = converter.convert(responseText);

			// 将执行结果添加到反思记录中,便于后续参考
			reflectionResult.setExecutionResult(step.getExecutionRes());
			// 将反思记录添加到步骤的历史中
			step.addReflectionRecord(reflectionResult);

			// 记录评估结果
			logger.debug("Step {} quality evaluation result: passed={}, feedback={}", step.getTitle(),
					reflectionResult.isPassed(), reflectionResult.getFeedback());

			// 返回是否通过评估
			return reflectionResult.isPassed();

		}
		catch (Exception e) {
			// 容错处理: 评估失败时默认通过
			logger.error("Quality evaluation failed, defaulting to pass: {}", e.getMessage());
			// 创建默认的反思记录
			ReflectionResult defaultResult = new ReflectionResult(true,
					"Evaluation failed, system default pass: " + e.getMessage(), step.getExecutionRes());
			// 添加到历史中
			step.addReflectionRecord(defaultResult);
			// 默认通过
			return true;
		}
	}

	/**
	 * 构建评估Prompt
	 * 
	 * 【作用】: 构建用于AI质量评估的Prompt,包含任务信息和执行结果
	 * 【设计理念】: 
	 * 1. 结构化Prompt: 使用清晰的格式,便于AI理解
	 * 2. 上下文完整: 提供足够的上下文信息,确保评估准确
	 * 3. 类型区分: 根据节点类型调整任务描述
	 * 4. Markdown格式: 使用Markdown增强可读性
	 * 【在项目中的地位】: 这是AI评估的基础,直接影响评估质量
	 * 
	 * 【Prompt结构】:
	 * Please evaluate the completion quality of the following {taskType}:
	 * 
	 * **Task Title:** {title}
	 * 
	 * **Task Description:** {description}
	 * 
	 * **Completion Result:**
	 * {executionResult}
	 * 
	 * @param step 计划步骤对象
	 * @param nodeType 节点类型("researcher"或"coder")
	 * @return 评估Prompt字符串
	 */
	private String buildEvaluationPrompt(Plan.Step step, String nodeType) {
		// 根据节点类型生成任务描述
		String taskTypeDescription = switch (nodeType) {
			case "researcher" -> "research task"; // 研究任务
			case "coder" -> "coding task"; // 编程任务
			default -> "task"; // 通用任务
		};

		// 使用多行字符串构建结构化Prompt
		return String.format("""
				Please evaluate the completion quality of the following %s:

				**Task Title:** %s

				**Task Description:** %s

				**Completion Result:**
				%s
				""", taskTypeDescription, step.getTitle(), step.getDescription(), step.getExecutionRes());
	}

	/**
	 * 获取反思尝试次数
	 * 
	 * 【作用】: 统计当前步骤已经进行了多少次反思尝试
	 * 【设计理念】: 
	 * 1. 优先使用反思历史: 从reflectionHistory列表中获取
	 * 2. 兼容旧格式: 支持从状态字符串中解析(向后兼容)
	 * 3. 容错设计: 解析失败时返回0
	 * 【在项目中的地位】: 这是防止无限循环的关键
	 * 
	 * 【统计方式】:
	 * 1. 新版本: 统计reflectionHistory列表的大小
	 * 2. 旧版本: 从状态字符串中解析,如"waiting_reflecting_attempt_2"
	 * 
	 * @param step 计划步骤对象
	 * @return 反思尝试次数
	 */
	private int getReflectionAttemptCount(Plan.Step step) {
		// 优先从反思历史中获取
		if (step.getReflectionHistory() != null) {
			return step.getReflectionHistory().size();
		}

		// 兼容旧格式: 从状态字符串中解析
		// 旧格式如: "waiting_reflecting_attempt_2_researcher_0"
		String status = step.getExecutionStatus();
		if (status != null && status.contains("_attempt_")) {
			try {
				String[] parts = status.split("_attempt_");
				if (parts.length > 1) {
					// 提取尝试次数
					return Integer.parseInt(parts[1].split("_")[0]);
				}
			}
			catch (NumberFormatException e) {
				logger.debug("Failed to parse reflection attempt count: {}", status);
			}
		}
		// 解析失败,返回0
		return 0;
	}

	/**
	 * 增加反思尝试次数
	 * 
	 * 【作用】: 在状态字符串中增加尝试次数标记
	 * 【设计理念】: 
	 * 1. 状态字符串编码: 将尝试次数编码到状态字符串中
	 * 2. 向后兼容: 保持旧格式的状态字符串
	 * 3. 简单可靠: 使用字符串拼接,易于理解和维护
	 * 【在项目中的地位】: 这是跟踪尝试次数的关键
	 * 
	 * 【状态字符串格式】:
	 * - 原始: "waiting_reflecting_researcher_0"
	 * - 第1次: "waiting_reflecting_attempt_1_researcher_0"
	 * - 第2次: "waiting_reflecting_attempt_2_researcher_0"
	 * 
	 * @param step 计划步骤对象
	 */
	private void incrementReflectionAttemptCount(Plan.Step step) {
		// 获取当前尝试次数
		int currentCount = getReflectionAttemptCount(step);
		// 提取状态的基础部分(去掉尝试次数)
		String baseStatus = step.getExecutionStatus().split("_attempt_")[0];
		// 拼接新的状态字符串,增加尝试次数
		step.setExecutionStatus(baseStatus + "_attempt_" + (currentCount + 1));
	}

	// ==================== 内部类 ====================

	/**
	 * 反思处理结果类
	 * 
	 * 【类的作用】: 封装反思处理的结果,指示是否需要继续执行任务
	 * 【设计理念】: 
	 * 1. 值对象模式: 不可变对象,确保线程安全
	 * 2. 工厂方法: 提供静态工厂方法创建实例
	 * 3. 语义清晰: 方法名明确表达意图
	 * 【在项目中的地位】: 这是反思流程的控制信号
	 * 
	 * 【使用场景】:
	 * - continueProcessing(): 继续执行任务(正常执行或重新执行)
	 * - skipProcessing(): 跳过执行(已完成或等待下次处理)
	 */
	public static class ReflectionHandleResult {

		/**
		 * 是否继续处理
		 * 
		 * 【作用】: 控制节点是否继续执行任务
		 * 【true】: 继续执行(正常执行或重新执行)
		 * 【false】: 跳过执行(已完成或等待下次处理)
		 */
		private final boolean shouldContinueProcessing;

		/**
		 * 私有构造函数
		 * 
		 * 【设计理念】: 强制使用工厂方法,确保语义清晰
		 */
		private ReflectionHandleResult(boolean shouldContinueProcessing) {
			this.shouldContinueProcessing = shouldContinueProcessing;
		}

		/**
		 * 创建"继续处理"结果
		 * 
		 * 【使用场景】: 
		 * - 任务需要正常执行
		 * - 任务需要重新执行(质量不达标后)
		 * 
		 * @return ReflectionHandleResult实例
		 */
		public static ReflectionHandleResult continueProcessing() {
			return new ReflectionHandleResult(true);
		}

		/**
		 * 创建"跳过处理"结果
		 * 
		 * 【使用场景】: 
		 * - 任务已完成(质量达标)
		 * - 任务等待下次处理(质量不达标后)
		 * - 任务达到最大尝试次数(强制通过)
		 * 
		 * @return ReflectionHandleResult实例
		 */
		public static ReflectionHandleResult skipProcessing() {
			return new ReflectionHandleResult(false);
		}

		/**
		 * 判断是否应该继续处理
		 * 
		 * @return true表示继续处理,false表示跳过处理
		 */
		public boolean shouldContinueProcessing() {
			return shouldContinueProcessing;
		}

	}

}
