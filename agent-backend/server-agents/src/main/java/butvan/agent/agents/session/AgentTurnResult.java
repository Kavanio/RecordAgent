package butvan.agent.agents.session;

import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;

/** 已持久化的精确轮次结果；只有生产线程释放运行槽位后才向非 HTTP 调用方发布。 */
public record AgentTurnResult(String runId, String sessionId, String turnId,
                              MessageStatus status, String content) {
}
