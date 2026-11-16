package butvan.agent.agents.review;

import butvan.agent.agents.memory.MemorySourceRegistry;

/**
 * 监听用户在桌面或飞书渠道完成发言的轻量通知端口。
 *
 * <p>用于即时重置主动陪伴的未回复计数并安排异步增量学习；实现方不得阻塞对话主流程。</p>
 */
public interface UserTurnActivityListener {
    void onUserTurnRecorded(String ownerId, String sessionId, MemorySourceRegistry.Channel channel);
}
