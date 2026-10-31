package butvan.agent.network.review.life;

import java.time.*;
import java.util.*;

/** 生活复盘独立 HTTP DTO；不暴露内部 owner、代次或接收方凭据。 */
public final class LifeReviewDtos {
    private LifeReviewDtos() {}
    public record SaveSettings(int expectedRevision, boolean enabled, Set<String> sources, String providerId,
                               String modelName, String timezone, int historyDays, int dailyLimit,
                               boolean proactive, boolean feishu, int idleCheckinDays,
                               boolean weatherAlertEnabled, boolean expenseAlertEnabled,
                               boolean serverAlertEnabled, String serverProbeUrl, String serverProbeName) {
        public SaveSettings(int expectedRevision, boolean enabled, Set<String> sources, String providerId,
                String modelName, String timezone, int historyDays, int dailyLimit,
                boolean proactive, boolean feishu, int idleCheckinDays,
                boolean weatherAlertEnabled, boolean expenseAlertEnabled) {
            this(expectedRevision, enabled, sources, providerId, modelName, timezone, historyDays, dailyLimit,
                    proactive, feishu, idleCheckinDays, weatherAlertEnabled, expenseAlertEnabled,
                    false, "", "应用服务器");
        }
        public SaveSettings(int expectedRevision, boolean enabled, Set<String> sources, String providerId,
                String modelName, String timezone, int historyDays, int dailyLimit,
                boolean proactive, boolean feishu, int idleCheckinDays) {
            this(expectedRevision, enabled, sources, providerId, modelName, timezone, historyDays, dailyLimit,
                    proactive, feishu, idleCheckinDays, false, false, false, "", "应用服务器");
        }
        public SaveSettings(int expectedRevision, boolean enabled, Set<String> sources, String providerId,
                String modelName, String timezone, int historyDays, int dailyLimit,
                boolean proactive, boolean feishu) {
            this(expectedRevision, enabled, sources, providerId, modelName, timezone, historyDays, dailyLimit,
                    proactive, feishu, 0, false, false, false, "", "应用服务器");
        }
    }
    public record Consent(int expectedRevision, String policyVersion, boolean confirmed) {}
    public record Prepare(LocalDate day, int expectedVersion, boolean supplement) {}
    public record Feedback(String decision, String text) {}
    public record EventCandidate(String topic, String contentId, String evidenceId, String eventKind) {}
    public record SaveHabit(int expectedRevision, int availableHour, String source, boolean active) {}
    public record Retry(boolean confirmed) {}
    public record Reply(String requestId, String text) {
        @Override public String toString() { return "Reply[正文已隐藏]"; }
    }
    public record SettingsView(int revision, boolean enabled, Set<String> sources, String providerId,
                               String modelName, String timezone, int historyDays, int dailyLimit,
                               boolean proactive, boolean feishu, boolean authorized, String policyVersion,
                               boolean channelAvailable, String channelLabel, int idleCheckinDays,
                               boolean weatherAlertEnabled, boolean expenseAlertEnabled,
                               boolean serverAlertEnabled, String serverProbeUrl, String serverProbeName) {
        public SettingsView(int revision, boolean enabled, Set<String> sources, String providerId,
                String modelName, String timezone, int historyDays, int dailyLimit,
                boolean proactive, boolean feishu, boolean authorized, String policyVersion,
                boolean channelAvailable, String channelLabel, int idleCheckinDays,
                boolean weatherAlertEnabled, boolean expenseAlertEnabled) {
            this(revision, enabled, sources, providerId, modelName, timezone, historyDays, dailyLimit,
                    proactive, feishu, authorized, policyVersion, channelAvailable, channelLabel,
                    idleCheckinDays, weatherAlertEnabled, expenseAlertEnabled, false, "", "应用服务器");
        }
        public SettingsView(int revision, boolean enabled, Set<String> sources, String providerId,
                String modelName, String timezone, int historyDays, int dailyLimit,
                boolean proactive, boolean feishu, boolean authorized, String policyVersion,
                boolean channelAvailable, String channelLabel, int idleCheckinDays) {
            this(revision, enabled, sources, providerId, modelName, timezone, historyDays, dailyLimit,
                    proactive, feishu, authorized, policyVersion, channelAvailable, channelLabel,
                    idleCheckinDays, false, false, false, "", "应用服务器");
        }
    }
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
    /** 仅由用户消息时间戳统计的作息建议；不含正文，不自动写入作息。 */
    public record HabitSuggestionView(boolean available, Integer suggestedHour, int sampleCount, int activeDays,
                                      int windowDays, List<Integer> hourCounts, String note) {}
    public record NudgeView(String id, String kind, String text, String state, Instant createdAt,
                            Instant sentAt, String feedback) {}
    public record NudgeFeedback(String decision) {}
}
