package butvan.agent.network.review.life;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Agent 系统性认知与动态不定时主动问候领域模型与 DTO。
 */
public final class LifeCompanionModels {
    private LifeCompanionModels() {}

    /**
     * 对用户的系统性认知（4 大核心维度）。
     */
    public record Cognition(
            String backgroundAndPreferences,
            String routineAndBusyHours,
            String activeFocusAndBlockers,
            String pendingHooksAndCuriosities,
            int busyStartHour,
            int busyEndHour,
            Instant updatedAt
    ) {
        public Cognition {
            backgroundAndPreferences = backgroundAndPreferences == null ? "" : backgroundAndPreferences.strip();
            routineAndBusyHours = routineAndBusyHours == null ? "" : routineAndBusyHours.strip();
            activeFocusAndBlockers = activeFocusAndBlockers == null ? "" : activeFocusAndBlockers.strip();
            pendingHooksAndCuriosities = pendingHooksAndCuriosities == null ? "" : pendingHooksAndCuriosities.strip();
            busyStartHour = (busyStartHour < 0 || busyStartHour > 23) ? 9 : busyStartHour;
            busyEndHour = (busyEndHour < 0 || busyEndHour > 23) ? 18 : busyEndHour;
        }

        public static Cognition defaults() {
            return new Cognition(
                    "",
                    "工作日白天（约 09:00—18:00）通常在忙，傍晚至晚间较常互动",
                    "",
                    "",
                    9,
                    18,
                    null
            );
        }

        public boolean isEmpty() {
            return backgroundAndPreferences.isBlank()
                    && activeFocusAndBlockers.isBlank()
                    && pendingHooksAndCuriosities.isBlank();
        }
    }

    /**
     * 动态主动节律状态（非固定死时间，根据忙闲与未回复情况动态演进）。
     */
    public record RhythmState(
            boolean companionEnabled,
            int unansweredCount,
            boolean busyDeferred,
            String lastOutreachHook,
            Instant lastLearnedAt,
            String lastDataFingerprint,
            Instant lastOutreachAt,
            Instant lastUserActiveAt,
            Instant nextReachAt
    ) {
        public RhythmState {
            lastOutreachHook = lastOutreachHook == null ? "" : lastOutreachHook.strip();
            lastDataFingerprint = lastDataFingerprint == null ? "" : lastDataFingerprint.strip();
        }

        public static RhythmState defaults() {
            return new RhythmState(false, 0, false, "", null, "", null, null, null);
        }
    }

    /**
     * 待桌面客户端弹出的系统原生通知项（与唯一主对话绑定）。
     */
    public record DesktopNotice(
            String id,
            String sessionId,
            String kind,
            String title,
            String content,
            Instant createdAt,
            boolean acknowledged
    ) {}

    /**
     * 持久化在用户工作区 companion/state.json 的完整状态。
     */
    public record CompanionState(
            Cognition cognition,
            RhythmState rhythm,
            List<DesktopNotice> desktopNotices
    ) {
        public CompanionState {
            cognition = cognition == null ? Cognition.defaults() : cognition;
            rhythm = rhythm == null ? RhythmState.defaults() : rhythm;
            desktopNotices = desktopNotices == null ? List.of() : List.copyOf(desktopNotices);
        }

        public static CompanionState defaults() {
            return new CompanionState(Cognition.defaults(), RhythmState.defaults(), List.of());
        }
    }

    /**
     * 返回给前端的完整主动陪伴与认知视图。
     */
    public record CompanionView(
            boolean companionEnabled,
            String primarySessionId,
            String primarySessionTitle,
            String activeModelLabel,
            boolean feishuConnected,
            String feishuLabel,
            Cognition cognition,
            RhythmState rhythm,
            List<DesktopNotice> pendingDesktopNotices
    ) {}

    /**
     * 用户手动修改系统性认知请求。
     */
    public record SaveCognitionRequest(
            String backgroundAndPreferences,
            String routineAndBusyHours,
            String activeFocusAndBlockers,
            String pendingHooksAndCuriosities,
            Integer busyStartHour,
            Integer busyEndHour
    ) {}

    /**
     * 一键开启/关闭主动陪伴与系统性学习请求。
     */
    public record EnableCompanionRequest(
            boolean enabled,
            Boolean feishu,
            Boolean weatherAlertEnabled,
            Boolean expenseAlertEnabled,
            Boolean serverAlertEnabled,
            String serverProbeUrl,
            String serverProbeName,
            Boolean mailAlertEnabled,
            String imapHost,
            Integer imapPort,
            String imapUsername,
            String imapPassword,
            String mailSenderWhitelist,
            String mailSubjectKeywords,
            String providerId,
            String modelName,
            String timezone
    ) {
        public Set<String> allSources() {
            return Set.of("BUSINESS", "JOURNAL", "CHAT", "FINANCE", "GOALS");
        }
    }

    /**
     * 手动触发一次主动问候请求（可选指定想聊的由头）。
     */
    public record ReachOutRequest(String reasonHint) {}
}
