package com.alibaba.cloud.ai.example.deepresearch.node;

import com.alibaba.cloud.ai.example.deepresearch.config.ShortTermMemoryProperties;
import com.alibaba.cloud.ai.example.deepresearch.memory.ShortTermMemoryRepository;
import com.alibaba.cloud.ai.example.deepresearch.model.dto.memory.ShortUserRoleExtractResult;
import com.alibaba.cloud.ai.example.deepresearch.util.JsonUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.TemplateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 短期用户角色记忆节点
 *
 * 【类的作用】
 * 1. 提取用户的角色特征和偏好信息：通过AI分析用户对话，提取职业、兴趣、专业领域等信息
 * 2. 维护用户的短期记忆：实现个性化服务，记住用户的上下文
 * 3. 根据历史对话记录，动态更新用户画像：避免信息过时
 * 4. 为后续节点提供用户上下文信息：提升交互体验
 *
 * 【设计理念】
 * 1. 用户画像构建：通过AI分析用户对话，提取角色特征
 * 2. 记忆融合：智能融合历史记忆和当前提取结果
 * 3. 置信度评估：通过置信度判断是否更新记忆
 * 4. 渐进式更新：避免频繁更新，保持记忆稳定性
 * 5. 结构化存储：使用JSON格式存储，便于查询和分析
 *
 * 【在项目中的地位】
 * - 这是深度研究系统的"个性化引擎"
 * - 位于工作流的最前端，为所有后续节点提供用户上下文
 * - 实现了AI系统的个性化能力
 * - 提升用户体验和交互质量
 *
 * 【工作流程】
 * 1. 获取最近n轮用户提问
 *    ↓
 * 2. 使用AI提取当前用户角色信息
 *    ↓
 * 3. 与历史记忆进行智能融合
 *    ↓
 * 4. 保存或更新短期记忆
 *    ↓
 * 5. 将记忆传递给后续节点
 *
 * 【为什么这么设计】
 * 1. 个性化需求：不同用户有不同的背景和需求，需要个性化服务
 * 2. 上下文连续性：多轮对话需要记住用户信息，避免重复询问
 * 3. 动态更新：用户角色可能随时间变化，需要动态调整
 * 4. 质量保证：通过置信度评估，避免错误信息污染记忆
 *
 * 【项目中的使用】
 * - 在DeepResearchConfiguration中作为StateGraph的第一个节点被注册
 * - 每次用户发起查询时，该节点都会被调用
 * - 调用时机：StateGraph执行流程的第一步，在CoordinatorNode之前
 * - 调用方式：stateGraph.addNode("short_user_role_memory", shortUserRoleMemoryNode)
 *
 * @author benym
 */
public class ShortUserRoleMemoryNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(ShortUserRoleMemoryNode.class);

	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final String ZONE_ASIA_SHANGHAI = "Asia/Shanghai";

	private static final String USER_ID = "MOCK_USER_ID";

	/**
	 * 短期记忆AI代理
	 * 负责提取和融合用户角色信息
	 */
	private final ChatClient shortMemoryAgent;

	/**
	 * 短期记忆配置属性
	 * 控制短期记忆的行为，如是否启用、历史消息数量、引导范围等
	 */
	private final ShortTermMemoryProperties shortTermMemoryProperties;

	/**
	 * 短期记忆存储库
	 * 负责用户角色记忆的持久化存储和查询
	 */
	private final ShortTermMemoryRepository shortTermMemoryRepository;

	/**
	 * Bean输出转换器
	 * 将AI输出的JSON格式转换为ShortUserRoleExtractResult对象
	 */
	private final BeanOutputConverter<ShortUserRoleExtractResult> converter;

	/**
	 * 构造函数
	 * 依赖注入所需的组件
	 *
	 * @param shortMemoryAgent 短期记忆AI代理
	 * @param shortTermMemoryProperties 短期记忆配置属性
	 * @param shortTermMemoryRepository 短期记忆存储库
	 */
	public ShortUserRoleMemoryNode(ChatClient shortMemoryAgent, ShortTermMemoryProperties shortTermMemoryProperties,
			ShortTermMemoryRepository shortTermMemoryRepository) {
		this.shortMemoryAgent = shortMemoryAgent;
		this.shortTermMemoryProperties = shortTermMemoryProperties;
		this.shortTermMemoryRepository = shortTermMemoryRepository;
		this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
		});
	}

	/**
	 * 核心执行方法
	 * 实现NodeAction接口，被StateGraph调用
	 *
	 * 【调用时机】
	 * - 在StateGraph执行流程的第一步被调用
	 * - 每次用户发起查询时都会执行
	 * - 在CoordinatorNode之前执行
	 *
	 * 【执行流程】
	 * 1. 检查是否启用短期记忆功能
	 * 2. 获取最近n轮用户提问
	 * 3. 调用AI提取当前用户角色信息
	 * 4. 与历史记忆进行智能融合
	 * 5. 根据引导范围决定是否传递记忆给后续节点
	 *
	 * @param state 全局状态对象，包含会话ID、用户查询等信息
	 * @return 更新后的状态Map，包含short_user_role_memory和short_user_role_next_node
	 * @throws Exception 执行过程中可能抛出的异常
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Map<String, Object> updated = new HashMap<>();
		
		// 步骤1：检查是否启用短期记忆功能
		// 如果未启用，直接跳到CoordinatorNode
		if (!shortTermMemoryProperties.isEnabled()) {
			updated.put("short_user_role_next_node", "coordinator");
			return updated;
		}
		
		logger.info("short_user_role_memory node is running.");
		
		// 获取引导范围配置
		// NONE: 不使用短期记忆
		// ONCE: 只在第一次使用
		// ALWAYS: 每次都使用
		ShortTermMemoryProperties.GuideScope guideScope = shortTermMemoryProperties.getUserRoleMemory().getGuideScope();
		
		try {
			// 步骤2：获取最近n轮用户提问
			// 用于AI分析用户的角色特征
			String historyUserMessages = buildHistoryUserMessages(state);
			
			// 步骤3：调用AI提取当前用户角色信息
			// 使用大模型分析用户查询和历史对话，提取角色特征
			ShortUserRoleExtractResult currentResult = extractShortTermMemory(state, historyUserMessages);
			
			// 步骤4：保存或更新短期记忆
			// 与历史记忆进行智能融合，使用置信度评估是否更新
			ShortUserRoleExtractResult mergeResult = saveOrUpdateShortTermMemory(state, currentResult);
			
			logger.info("generated short user role memory: {}", JsonUtil.toJson(mergeResult));
			
			// 步骤5：根据引导范围决定是否传递记忆给后续节点
			// 如果引导范围为NONE，不传递记忆
			if (guideScope.equals(ShortTermMemoryProperties.GuideScope.NONE)) {
				updated.put("short_user_role_next_node", "coordinator");
				return updated;
			}
			
			// 如果引导范围为ONCE，且已经有历史消息，不传递记忆
			if (StringUtils.hasText(historyUserMessages)
					&& guideScope.equals(ShortTermMemoryProperties.GuideScope.ONCE)) {
				updated.put("short_user_role_memory", "");
				updated.put("short_user_role_next_node", "coordinator");
				return updated;
			}
			
			// 否则，传递记忆给后续节点
			updated.put("short_user_role_memory", JsonUtil.toJson(mergeResult));
			updated.put("short_user_role_next_node", "coordinator");
		}
		catch (Exception e) {
			// 异常处理：即使提取失败，也要继续执行后续流程
			logger.error("short user role memory extraction failed, conversationId: {}", StateUtil.getSessionId(state),
					e);
			updated.put("short_user_role_next_node", "coordinator");
		}
		
		return updated;
	}

	/**
	 * 构建历史用户消息
	 * 从短期记忆库中获取最近n轮用户提问，并格式化为字符串
	 *
	 * 【调用时机】
	 * - 在apply方法中被调用
	 * - 在extractShortTermMemory之前调用
	 *
	 * 【设计思路】
	 * - 从短期记忆库中获取最近n轮用户提问
	 * - 格式化为"第X轮, 用户消息:XXX"的格式
	 * - 将当前用户提问保存到短期记忆库
	 *
	 * @param state 全局状态对象
	 * @return 格式化后的历史用户消息字符串
	 */
	private String buildHistoryUserMessages(OverAllState state) {
		// 从短期记忆库中获取最近n轮用户提问
		// n由配置属性historyUserMessagesNum控制
		List<String> recentUserQueries = shortTermMemoryRepository.getRecentUserQueries(StateUtil.getSessionId(state),
				shortTermMemoryProperties.getUserRoleMemory().getHistoryUserMessagesNum());
		
		// 如果没有历史用户提问，保存当前用户提问后返回空字符串
		if (CollectionUtils.isEmpty(recentUserQueries)) {
			saveUserQuery(state);
			return "";
		}
		
		// 格式化历史用户消息
		// 格式为"第X轮, 用户消息:XXX"
		StringBuilder historyUserMessages = new StringBuilder();
		for (int i = 0; i < recentUserQueries.size(); i++) {
			String userMessage = String.format("第%s轮, 用户消息:%s\n", i + 1, recentUserQueries.get(i));
			historyUserMessages.append(userMessage);
		}
		
		// 保存当前用户提问到短期记忆库
		saveUserQuery(state);
		
		return historyUserMessages.toString();
	}

	/**
	 * 保存用户提问到短期记忆库
	 * 将当前用户提问保存到短期记忆库，用于后续的历史消息构建
	 *
	 * 【调用时机】
	 * - 在buildHistoryUserMessages方法中被调用
	 * - 每次构建历史消息时都会调用
	 *
	 * 【设计思路】
	 * - 将用户提问封装为UserMessage对象
	 * - 添加创建时间元数据
	 * - 保存到短期记忆库
	 *
	 * @param state 全局状态对象
	 */
	private void saveUserQuery(OverAllState state) {
		// 创建元数据，包含创建时间
		Map<String, Object> metaData = new HashMap<>();
		metaData.put("create_time", LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)));
		
		// 构建UserMessage对象
		UserMessage userMessage = UserMessage.builder()
				.text(StateUtil.getQuery(state))
				.metadata(metaData)
				.build();
		
		// 保存到短期记忆库
		shortTermMemoryRepository.saveUserQuery(StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singletonList(userMessage)));
	}

	/**
	 * 提取用户角色短期记忆
	 * 使用AI分析用户查询和历史对话，提取用户角色特征
	 *
	 * 【调用时机】
	 * - 在apply方法中被调用
	 * - 在buildHistoryUserMessages之后调用
	 *
	 * 【设计思路】
	 * - 构建提取提示词，包含用户查询和历史消息
	 * - 调用AI代理进行角色提取
	 * - 将AI输出的JSON转换为ShortUserRoleExtractResult对象
	 * - 填充结果对象的元数据
	 *
	 * @param state 全局状态对象
	 * @param historyUserMessages 历史用户提问
	 * @return 提取的用户角色信息
	 * @throws IOException IO异常
	 */
	private ShortUserRoleExtractResult extractShortTermMemory(OverAllState state, String historyUserMessages)
			throws IOException {
		// 构建提取提示词
		// 包含用户查询和历史消息
		List<Message> messages = Collections
			.singletonList(TemplateUtil.getShortMemoryExtractMessage(StateUtil.getQuery(state), historyUserMessages));
		
		logger.debug("extract messages: {}", messages);
		
		// 调用AI代理进行角色提取
		ChatResponse chatResponse = callShortMemoryAgent(messages);
		
		// 获取AI输出的文本
		String text = chatResponse.getResult().getOutput().getText();
		assert text != null;
		
		// 将JSON转换为ShortUserRoleExtractResult对象
		ShortUserRoleExtractResult result = converter.convert(text);
		assert result != null;
		
		// 填充结果对象的元数据
		fillResult(state, result);
		
		return result;
	}

	/**
	 * 调用短期记忆Agent
	 * 使用AI代理进行角色提取或记忆融合
	 *
	 * 【调用时机】
	 * - 在extractShortTermMemory方法中被调用
	 * - 在mergeAndUpdateShortTermMemory方法中被调用
	 *
	 * 【设计思路】
	 * - 使用BeanOutputConverter的格式规范AI输出
	 * - 传递消息列表给AI代理
	 * - 返回AI的响应
	 *
	 * @param messages 系统消息列表
	 * @return AI响应
	 */
	private ChatResponse callShortMemoryAgent(List<Message> messages) {
		// 调用AI代理
		// 使用BeanOutputConverter的格式规范AI输出
		return shortMemoryAgent.prompt(converter.getFormat()).messages(messages).call().chatResponse();
	}

	/**
	 * 填充结果对象
	 * 为提取的用户角色信息填充元数据
	 *
	 * 【调用时机】
	 * - 在extractShortTermMemory方法中被调用
	 * - 在AI提取结果返回后调用
	 *
	 * 【设计思路】
	 * - 填充用户ID
	 * - 填充用户查询
	 * - 填充会话ID
	 * - 填充创建时间
	 *
	 * @param state 全局状态对象
	 * @param result 抽取结果对象
	 */
	private void fillResult(OverAllState state, ShortUserRoleExtractResult result) {
		// 填充用户ID
		result.setUserId(USER_ID);
		
		// 填充用户查询
		result.setUserQuery(StateUtil.getQuery(state));
		
		// 填充会话ID
		result.setConversationId(StateUtil.getSessionId(state));
		
		// 填充创建时间
		result.setCreatTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
	}

	/**
	 * 保存或更新短期记忆
	 * 与历史记忆进行智能融合，使用置信度评估是否更新
	 *
	 * 【调用时机】
	 * - 在apply方法中被调用
	 * - 在extractShortTermMemory之后调用
	 *
	 * 【设计思路】
	 * - 如果没有历史记忆，直接保存当前结果
	 * - 如果有历史记忆，比较置信度
	 * - 如果当前结果的置信度>=历史结果的置信度，融合历史记忆后更新
	 * - 如果当前结果的置信度<历史结果的置信度，保持历史记忆不变，只更新交互次数和时间
	 *
	 * 【为什么这么设计】
	 * - 置信度评估：避免低质量信息污染记忆
	 * - 智能融合：结合历史和当前信息，提高准确性
	 * - 渐进式更新：避免频繁更新，保持记忆稳定性
	 * - 交互计数：记录交互次数，用于后续分析
	 *
	 * @param state 全局状态对象
	 * @param currentResult 当前提取结果
	 * @return 融合后结果
	 * @throws IOException IO异常
	 */
	private ShortUserRoleExtractResult saveOrUpdateShortTermMemory(OverAllState state,
			ShortUserRoleExtractResult currentResult) throws IOException {
		// 从短期记忆库中查找最新的用户角色记忆
		Message historyShortResult = shortTermMemoryRepository.findLatestExtractMessage(USER_ID,
				StateUtil.getSessionId(state));
		
		// 如果没有历史用户角色记忆，直接保存当前结果
		if (historyShortResult == null) {
			SystemMessage newShortMemory = new SystemMessage(JsonUtil.toJson(currentResult));
			shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
					new ArrayList<>(Collections.singleton(newShortMemory)));
			return currentResult;
		}
		
		// 解析历史用户角色记忆
		ShortUserRoleExtractResult latestExtract = converter.convert(historyShortResult.getText());
		Double latestConfidence = Objects.requireNonNull(latestExtract).getConversationAnalysis().getConfidenceScore();
		Double currentConfidence = currentResult.getConversationAnalysis().getConfidenceScore();
		
		// 比较置信度
		// 如果当前结果的置信度>=历史结果的置信度，融合历史用户角色信息后更新短期记忆
		// 是否真正融合需要由LLM结合历史判定
		// 例如：当前轮用户进行角色扮演，但结合历史看，这不是用户的核心角色信息
		if (currentConfidence >= latestConfidence) {
			return mergeAndUpdateShortTermMemory(state, currentResult, latestExtract);
		}
		
		// 否则，保持历史短期记忆不变，仅更新交互次数和更新时间
		latestExtract.getConversationAnalysis()
			.setInteractionCount(latestExtract.getConversationAnalysis().getInteractionCount() + 1);
		latestExtract.setUpdateTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
		
		// 保存更新后的历史记忆
		SystemMessage newShortMemory = new SystemMessage(JsonUtil.toJson(latestExtract));
		shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singleton(newShortMemory)));
		
		// 返回当前语句的提取结果，指令跟随用户最新的输入
		return currentResult;
	}

	/**
	 * 合并并更新短期记忆
	 * 使用AI融合当前提取结果和历史记忆
	 *
	 * 【调用时机】
	 * - 在saveOrUpdateShortTermMemory方法中被调用
	 * - 当当前结果的置信度>=历史结果的置信度时调用
	 *
	 * 【设计思路】
	 * - 获取历史记忆轨迹
	 * - 构建融合提示词，包含当前结果、历史结果和历史轨迹
	 * - 调用AI进行智能融合
	 * - 保存融合后的结果
	 *
	 * 【为什么这么设计】
	 * - 智能融合：使用AI判断哪些信息应该保留
	 * - 历史轨迹：提供更多上下文，提高融合准确性
	 * - 相似度阈值：控制融合的严格程度
	 *
	 * @param state 全局状态对象
	 * @param current 当前提取结果
	 * @param latest 最近一次提取结果
	 * @return 融合后结果
	 * @throws IOException IO异常
	 */
	private ShortUserRoleExtractResult mergeAndUpdateShortTermMemory(OverAllState state,
			ShortUserRoleExtractResult current, ShortUserRoleExtractResult latest) throws IOException {
		// 获取历史记忆轨迹
		// 用于AI判断哪些信息应该保留
		List<Message> messageTrack = shortTermMemoryRepository.findMessageTrack(USER_ID, StateUtil.getSessionId(state));
		List<ShortUserRoleExtractResult> historyTracks = new ArrayList<>();
		if (!CollectionUtils.isEmpty(messageTrack)) {
			messageTrack.stream().map(message -> converter.convert(message.getText())).forEach(historyTracks::add);
		}
		
		// 组装融合提示词
		// 包含当前结果、历史结果和历史轨迹
		List<Message> updateMessages = Collections.singletonList(TemplateUtil.getShortMemoryUpdateMessage(current,
				latest, historyTracks, shortTermMemoryProperties.getUserRoleMemory().getUpdateSimilarityThreshold()));
		
		// 调用AI进行智能融合
		ChatResponse updateResponse = callShortMemoryAgent(updateMessages);
		
		// 获取融合结果
		String updateText = updateResponse.getResult().getOutput().getText();
		assert updateText != null;
		
		// 将JSON转换为ShortUserRoleExtractResult对象
		ShortUserRoleExtractResult mergedResult = converter.convert(updateText);
		assert mergedResult != null;
		
		// 填充融合结果的元数据
		mergedResult.setUserQuery(StateUtil.getQuery(state));
		mergedResult.setUpdateTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
		
		// 保存融合后的结果
		SystemMessage mergedMemory = new SystemMessage(JsonUtil.toJson(mergedResult));
		shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singleton(mergedMemory)));
		
		return mergedResult;
	}

}
