package butvan.agent.agents.memory;

import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;

/** 聊天与经历记忆的生命周期 seam；实现不得等待远端或改变聊天终态。 */
public interface MemoryTurnLifecycle {
    /** 用户原文成功落盘后通知，仅可信入口标注的普通用户文本可取得资格。 */
    void admitted(AgentUserCall request, String owner, String turnId);
    /** 权威 assistant 终态落盘后通知；权限暂停不调用，重复通知必须幂等。 */
    void completed(String owner, String sessionId, String turnId, MessageStatus status);

    static MemoryTurnLifecycle disabled() {
        return new MemoryTurnLifecycle() {
            public void admitted(AgentUserCall request, String owner, String turnId) { }
            public void completed(String owner, String sessionId, String turnId, MessageStatus status) { }
        };
    }
}
