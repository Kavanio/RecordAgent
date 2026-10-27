package butvan.agent.network.chat.dto;

import java.util.List;

/**
 * 桌面端发起聊天时可携带的用户可见内容、问题上下文与稳定资料引用。
 *
 * <p>图片以 Base64 随本轮一次性提交，不落盘、不进 transcript；格式与大小由
 * {@code AgentImages} 在服务端按文件头校验后拒绝。</p>
 */
public record AgentChatRequest(
        String runId,
        String sessionId,
        String content,
        String context,
        List<String> recordReferenceIds,
        AgentAnalysisContextRequest analysisContext,
        List<AgentChatImage> images) {

    /** 单张图片的 Base64 内容；mediaType 仅作提示，服务端以文件头为准。 */
    public record AgentChatImage(String mediaType, String dataBase64) { }

    /** 保留内部测试与非可取消调用方的兼容构造方式。 */
    public AgentChatRequest(
            String sessionId,
            String content,
            String context,
            List<String> recordReferenceIds,
            AgentAnalysisContextRequest analysisContext
    ) {
        this(null, sessionId, content, context, recordReferenceIds, analysisContext, List.of());
    }

    public AgentChatRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
