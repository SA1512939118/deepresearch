package com.alibaba.cloud.ai.example.deepresearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Short-term memory configuration properties
 *
 * @author benym
 */
@ConfigurationProperties(prefix = ShortTermMemoryProperties.PREFIX)
public class ShortTermMemoryProperties {

	public static final String PREFIX = DeepResearchProperties.PREFIX + ".short-term-memory";

	/**
	 * Whether short-term memory is enabled
	 */
	private boolean enabled = true;

	/**
	 * User role memory configuration
	 */
	private UserRoleMemory userRoleMemory;

	/**
	 * Conversation memory configuration
	 */
	private ConversationMemory conversationMemory;

	/**
	 * Type of memory storage
	 */
	private MemoryType memoryType = MemoryType.IN_MEMORY;

	/**
	 * User role memory configuration
	 */
	public static class UserRoleMemory {

		/**
		 * Scope of short-term memory guidance
		 */
		private GuideScope guideScope = GuideScope.EVERY;

		/**
		 * Similarity threshold for updating short-term memory
		 * - 当新提取的用户角色与已有记忆的相似度超过此阈值时，才更新记忆
		 * - 防止频繁更新记忆，保持记忆稳定性
		 * - 值越高，记忆越稳定，更新越少
		 * - 值越低，记忆更新越频繁
		 */
		private Double updateSimilarityThreshold = 0.8;

		/**
		 * The number of recent user questions for reference in user role extraction
		 * 用户角色提取时参考的最近用户问题数量
		 * - 提取用户角色时，会参考最近N条用户消息
		 * - 消息越多，提取的角色特征越准确
		 * - 消息越多，处理时间越长
		 * - 建议值: 5-20
		 */
		private int historyUserMessagesNum = 10;

		public GuideScope getGuideScope() {
			return guideScope;
		}

		public void setGuideScope(GuideScope guideScope) {
			this.guideScope = guideScope;
		}

		public Double getUpdateSimilarityThreshold() {
			return updateSimilarityThreshold;
		}

		public void setUpdateSimilarityThreshold(Double updateSimilarityThreshold) {
			this.updateSimilarityThreshold = updateSimilarityThreshold;
		}

		public int getHistoryUserMessagesNum() {
			return historyUserMessagesNum;
		}

		public void setHistoryUserMessagesNum(int historyUserMessagesNum) {
			this.historyUserMessagesNum = historyUserMessagesNum;
		}

	}

	/**
	 * Conversation memory configuration
	 */
	public static class ConversationMemory {

		/**
		 * Maximum number of messages stored in conversation memory
		 * - 限制对话记忆的大小，防止内存溢出
		 * - 当消息数量超过此值时，会删除最旧的消息
		 * - 值越大，保存的对话历史越长
		 * - 值越小，内存占用越少
		 */
		private Integer maxMessages = 100;

		public Integer getMaxMessages() {
			return maxMessages;
		}

		public void setMaxMessages(Integer maxMessages) {
			this.maxMessages = maxMessages;
		}

	}

	public enum GuideScope {

		/**
		 * No guidance
		 */
		NONE,

		/**
		 * Only in the first round of the guiding model
		 */
		ONCE,

		/**
		 * Each round will guide the model
		 */
		EVERY

	}

	public enum MemoryType {

		/**
		 * In-memory storage
		 */
		IN_MEMORY

		//可扩展为支持其他存储类型，如Redis、数据库等

	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public MemoryType getMemoryType() {
		return memoryType;
	}

	public void setMemoryType(MemoryType memoryType) {
		this.memoryType = memoryType;
	}

	public UserRoleMemory getUserRoleMemory() {
		return userRoleMemory;
	}

	public void setUserRoleMemory(UserRoleMemory userRoleMemory) {
		this.userRoleMemory = userRoleMemory;
	}

	public ConversationMemory getConversationMemory() {
		return conversationMemory;
	}

	public void setConversationMemory(ConversationMemory conversationMemory) {
		this.conversationMemory = conversationMemory;
	}

}
