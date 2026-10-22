package butvan.agent.network.review.dto;

import java.time.*;
import java.util.List;

/** 复盘记忆公开投影；不包含 owner、模型凭据或数据库路径。 */
public final class ReviewMemoryDtos {
    private ReviewMemoryDtos() {}
    public record ActionView(String id, String reviewId, String topicKey, String goalId, String title,
            String rationale, String criterion, String methodId, String state, int revision, LocalDate checkOn,
            String feedback, Instant createdAt, Instant updatedAt) {}
    public record ObservationView(String id, String reviewId, String topicKey, String content, String uncertainty,
            String state, int revision, String correction, Instant createdAt, Instant expiresAt) {}
    public record FeedbackRequest(String requestId, int expectedRevision, String decision,
            String title, String criterion, LocalDate checkOn, String feedback) {}
    public record CorrectionRequest(String requestId, int expectedRevision, String decision, String correction) {}
    public record MemoryView(List<ActionView> actions, List<ObservationView> observations) {}
}
