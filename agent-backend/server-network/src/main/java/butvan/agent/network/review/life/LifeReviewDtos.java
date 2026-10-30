package butvan.agent.network.review.life;

import java.time.*;
import java.util.*;

/** 生活复盘独立 HTTP DTO；不暴露内部 owner、代次或接收方凭据。 */
public final class LifeReviewDtos {
    private LifeReviewDtos() {}
    public record SaveSettings(int expectedRevision, boolean enabled, Set<String> sources, String providerId,
                               String modelName, String timezone, int historyDays, int dailyLimit,
                               boolean proactive, boolean feishu) {}
    public record Consent(int expectedRevision, String policyVersion, boolean confirmed) {}
    public record Prepare(LocalDate day, int expectedVersion, boolean supplement) {}
    public record Feedback(String decision, String text) {}
    public record EventCandidate(String topic, String contentId, String evidenceId, String eventKind) {}
    public record SaveHabit(int expectedRevision, int availableHour, String source, boolean active) {}
    public record SettingsView(int revision, boolean enabled, Set<String> sources, String providerId,
                               String modelName, String timezone, int historyDays, int dailyLimit,
                               boolean proactive, boolean feishu, boolean authorized, String policyVersion,
                               boolean channelAvailable, String channelLabel) {}
    public record MaterialView(String id, String kind, String sourceId, String occurredAt, String text, boolean truncated) {}
    public record FactView(String id, String domain, String metric, String value, String unit, String currency, String period) {}
    public record ItemView(String kind, String text, List<String> evidenceIds, String uncertainty) {}
    public record DeliveryView(String id, String channel, String state, int attempts) {}
    public record ContentView(String id, String themeId, LocalDate day, String timezone, int version, String state,
                              Instant sourceAsOf, Instant availableAt, String providerId, String modelName, String coverage, String message,
                              List<MaterialView> materials, List<FactView> facts, List<ItemView> items, List<String> questions,
                              List<DeliveryView> deliveries) {}
    public record CandidateView(String id, String topic, String sourceId, String contentId, String state,
                                Instant dueAt, Instant expiresAt, String feedback, String eventKind) {}
    public record HabitView(int revision, int availableHour, String source, String uncertainty,
                            Instant observedAt, Instant expiresAt, boolean active) {}
}
