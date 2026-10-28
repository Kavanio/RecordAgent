package butvan.agent.network.reset.dto;

import java.util.List;

/** 重置 HTTP 契约；不接受用户身份、路径或任意删除范围。 */
public final class ResetDtos {
    private ResetDtos() { }
    public record Preview(String confirmationToken, long expiresAt, String scopeVersion, long generation,
                          List<String> deletes, List<String> preserves, String limitation) { }
    public record Confirm(String confirmationToken, String confirmationText, String requestId, String scopeVersion) { }
    public record Retry(boolean confirmed) { }
    public record Status(String resetId, long revision, long generation, String status, List<String> completedSteps,
                         String message, boolean retryable, String remoteStatus) { }
}
