package butvan.agent.agents.agent;

import java.util.List;

/** 用户可见内容与发送给模型的上下文分离，支持带资料引用的命令。 */
public record AgentUserCall(
        String sessionId,
        String content,
        String context,
        List<String> ragContexts,
        String runId,
        String turnId) {

    /** 保留桌面端既有构造契约；只有持久化渠道需要预先指定轮次 ID。 */
    public AgentUserCall(String sessionId, String content, String context, List<String> ragContexts, String runId) {
        this(sessionId, content, context, ragContexts, runId, null);
    }

    public AgentUserCall(String sessionId, String context) {
        this(sessionId, context, context, List.of(), null, null);
    }

    public AgentUserCall {
        ragContexts = ragContexts == null ? List.of() : List.copyOf(ragContexts);
    }
}
