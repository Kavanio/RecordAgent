package butvan.agent.network.review.dto;

import java.time.*;
import java.util.List;

/** 复盘生成与投递的公开契约；正文只在详情返回，不写入日志。 */
public final class ReviewCoachDtos {
    private ReviewCoachDtos() {}

    public record PreviewRequest(String requestId, String period, LocalDate from, LocalDate to, String timezone,
                                 Boolean confirmedPaidCall) {}
    public record ReviewView(String id, String period, LocalDate from, LocalDate to, String timezone,
                             String state, boolean preview, String message, Instant createdAt, Instant finishedAt,
                             String coverageNote, List<Item> observations, List<Item> actions, List<Item> followUps,
                             List<EvidenceView> evidence) {}
    public record Item(String id, String topicKey, String kind, String text, String rationale, String criterion,
                       String methodId, String state, String uncertainty, List<String> evidenceIds) {}
    public record EvidenceView(String id, String domain, String metricKey, String value, String unit, String currency,
                               String period, String snapshotId) {}
    public record ReviewSummary(String id, String period, LocalDate from, LocalDate to, String state,
                                boolean preview, String message, Instant createdAt, Instant finishedAt) {}

    static Item action(butvan.agent.network.review.model.ReviewModels.Action value) {
        return new Item(value.id(), value.topicKey(), "ACTION", value.title(), value.rationale(), value.criterion(),
                value.methodId(), value.state(), "", List.of());
    }
    static Item observation(butvan.agent.network.review.model.ReviewModels.Observation value) {
        return new Item(value.id(), value.topicKey(), "OBSERVATION", value.content(), "", "", "", value.state(),
                value.uncertainty(), List.of());
    }
}
