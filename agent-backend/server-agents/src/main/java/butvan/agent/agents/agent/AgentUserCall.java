package butvan.agent.agents.agent;

import java.util.List;

/**
 * 用户可见内容与发送给模型的上下文分离，支持带资料引用的命令。
 *
 * <p>图片只在本轮内存中存在：持久化渠道在受理时可以把附件留在内存，
 * 但 transcript、checkpoint 和 Token 用量记录只保存 {@code content} 文本。</p>
 */
public record AgentUserCall(
        String sessionId,
        String content,
        String context,
        List<String> ragContexts,
        String runId,
        String turnId,
        List<AgentImageAttachment> images,
        butvan.agent.agents.memory.MemorySourceRegistry.Channel memoryChannel) {

    /** 旧调用没有可信来源标签，保持不参与远端经历保存的兼容行为。 */
    public AgentUserCall(String sessionId, String content, String context, List<String> ragContexts,
                         String runId, String turnId, List<AgentImageAttachment> images) {
        this(sessionId, content, context, ragContexts, runId, turnId, images, null);
    }

    /** 保留桌面端既有构造契约；只有持久化渠道需要预先指定轮次 ID。 */
    public AgentUserCall(String sessionId, String content, String context, List<String> ragContexts, String runId) {
        this(sessionId, content, context, ragContexts, runId, null, List.of());
    }

    /** 兼容无图片、但需要固定轮次 ID 的渠道调用。 */
    public AgentUserCall(String sessionId, String content, String context, List<String> ragContexts,
                         String runId, String turnId) {
        this(sessionId, content, context, ragContexts, runId, turnId, List.of());
    }

    public AgentUserCall(String sessionId, String context) {
        this(sessionId, context, context, List.of(), null, null, List.of());
    }

    public AgentUserCall {
        ragContexts = ragContexts == null ? List.of() : List.copyOf(ragContexts);
        images = images == null ? List.of() : List.copyOf(images);
    }
}
