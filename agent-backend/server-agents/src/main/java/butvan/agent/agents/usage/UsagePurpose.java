package butvan.agent.agents.usage;

/** 模型调用的业务用途，避免将后台调用混入聊天轮次。 */
public enum UsagePurpose {
    CHAT,
    SESSION_TITLE,
    /** 用户主动发起的模型 API 连接测试。 */
    MODEL_CONNECTION_TEST,
    PROFILE_MAINTENANCE,
    /** 自动／手动复盘草案生成；独立于聊天轮次。 */
    REPORT_REVIEW,
    CONTEXT_COMPACTION,
    BACKGROUND_AGENT
}
