package butvan.agent.network.review.dto;

import butvan.agent.network.review.model.ReviewModels.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 独立 HTTP 请求／响应；不向客户端暴露内部实体、密钥或账本原文。 */
public final class ReviewDtos {
    private ReviewDtos() {}

    public record SaveSettings(int expectedRevision, Boolean enabled, Set<String> periods, Set<String> domains,
                               int historyDays, int dailyLimit, String providerId, String modelName) {}
    public record SettingsView(boolean enabled, boolean authorized, boolean generationAvailable,
                               String capability, String policyVersion, Set<String> periods, Set<String> domains,
                               int historyDays, int dailyLimit, String providerId, String modelName,
                               String connectionFingerprint, int revision, GrantView grant) {}
    public record GrantRequest(int expectedSettingsRevision, String policyVersion, Boolean confirmed) {}
    public record RevokeRequest(int expectedRevision) {}
    public record GrantView(String id, String policyVersion, String state, int revision,
                            Set<String> periods, Set<String> domains, int historyDays, int dailyLimit,
                            String connectionFingerprint, Instant createdAt, Instant revokedAt) {
        public static GrantView from(Grant value) {
            return new GrantView(value.id(), value.policyVersion(), value.state(), value.revision(), value.periods(),
                    value.domains(), value.historyDays(), value.dailyLimit(), value.fingerprint(), value.createdAt(), value.revokedAt());
        }
    }
    public record CaptureRequest(LocalDate from, LocalDate to, String timezone) {}
    public record EraseRequest(int expectedSettingsRevision, Boolean confirmed) {}
    public record EraseResult(int deletedSnapshots, int deletedGoals, int settingsRevision) {}
    public record FactView(String domain, String metricKey, String subjectId, String currency, String unit, String value) {}
    public record SourceView(String domain, String state) {}
    public record SnapshotSummaryView(String id, LocalDate from, LocalDate to, String timezone, Instant sourceAsOf,
                                      int metricVersion, String fingerprint, Instant expiresAt) {
        public static SnapshotSummaryView from(SnapshotHeader value) {
            return new SnapshotSummaryView(value.id(), value.from(), value.to(), value.timezone(), value.sourceAsOf(),
                    value.metricVersion(), value.fingerprint(), value.expiresAt());
        }
    }
    public record SnapshotView(String id, LocalDate from, LocalDate to, String timezone, Instant sourceAsOf,
                               int metricVersion, String fingerprint, Instant expiresAt,
                               List<FactView> facts, List<SourceView> sources) {
        public static SnapshotView from(FactSnapshot value) {
            return new SnapshotView(value.id(), value.from(), value.to(), value.timezone(), value.sourceAsOf(),
                    value.metricVersion(), value.fingerprint(), value.expiresAt(),
                    value.facts().stream().map(f -> new FactView(f.domain(), f.metricKey(), f.subjectId(), f.currency(), f.unit(), Long.toString(f.value()))).toList(),
                    value.sources().stream().map(s -> new SourceView(s.domain(), s.state())).toList());
        }
    }
    public record SaveGoal(int expectedRevision, String title, String constraintText, String successCriterion, String state) {}
    public record GoalView(String id, String title, String constraintText, String successCriterion,
                           String state, int revision, Instant createdAt, Instant updatedAt) {
        public static GoalView from(Goal value) {
            return new GoalView(value.id(), value.title(), value.constraintText(), value.successCriterion(),
                    value.state(), value.revision(), value.createdAt(), value.updatedAt());
        }
    }
}
