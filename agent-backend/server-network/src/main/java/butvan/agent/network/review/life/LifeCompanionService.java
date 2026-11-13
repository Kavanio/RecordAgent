package butvan.agent.network.review.life;

import butvan.agent.agents.agent.AgentService;
import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.memory.MemorySourceRegistry;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.review.CompanionContextPort;
import butvan.agent.agents.review.PersonalDeliveryPort;
import butvan.agent.agents.review.UserTurnActivityListener;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionStatus;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.network.review.life.LifeCompanionModels.*;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import butvan.agent.network.review.model.ReviewModels.ModelIdentity;
import butvan.agent.network.review.service.ReviewModelCatalog;
import butvan.agent.network.review.service.ReviewPolicy;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主动陪伴核心协调器：管理四维系统性认知、动态节律门控、多端同步投递、实时 SSE 与上下文只读注入。
 */
@Slf4j
@Service
public class LifeCompanionService implements CompanionContextPort, UserTurnActivityListener {
    private static final Duration LEARN_COOLDOWN = Duration.ofHours(2);
    private static final Duration TURN_RELEARN_COOLDOWN = Duration.ofMinutes(15);

    private final LifeCompanionStore store;
    private final LifeReviewRepository repository;
    private final LifeAuthorizationService authorization;
    private final LifeDeliveryService deliveries;
    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final Clock automationClock;
    private final LifeCompanionCollector collector;
    private final LifeCompanionSynthesisService synthesis;
    private final ReviewModelCatalog modelCatalog;
    private final LifeCompanionStreamService streamService;
    private final AgentService agentService;
    private final LifeActivityService activity;
    private final ResetBarrier resetBarrier;
    private final List<PersonalDeliveryPort> channels;
    private final ExecutorService learnExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "companion-learn-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean learnScheduled = new AtomicBoolean(false);

    @Autowired
    public LifeCompanionService(
            LifeCompanionStore store,
            LifeReviewRepository repository,
            LifeAuthorizationService authorization,
            LifeDeliveryService deliveries,
            SessionCatalogService sessions,
            TranscriptService transcripts,
            Clock automationClock,
            LifeCompanionCollector collector,
            LifeCompanionSynthesisService synthesis,
            @Autowired(required = false) ReviewModelCatalog modelCatalog,
            @Autowired(required = false) LifeCompanionStreamService streamService,
            @Autowired(required = false) AgentService agentService,
            @Autowired(required = false) LifeActivityService activity,
            @Autowired(required = false) ResetBarrier resetBarrier,
            @Autowired(required = false) List<PersonalDeliveryPort> channels
    ) {
        this.store = store;
        this.repository = repository;
        this.authorization = authorization;
        this.deliveries = deliveries;
        this.sessions = sessions;
        this.transcripts = transcripts;
        this.automationClock = automationClock;
        this.collector = collector;
        this.synthesis = synthesis;
        this.modelCatalog = modelCatalog;
        this.streamService = streamService;
        this.agentService = agentService;
        this.activity = activity;
        this.resetBarrier = resetBarrier;
        this.channels = channels == null ? List.of() : channels;
    }

    public SseEmitter subscribe(String owner) {
        if (streamService == null) {
            throw new IllegalStateException("陪伴实时流服务未启用");
        }
        return streamService.subscribe(owner, view(owner));
    }

    public CompanionView view(String owner) {
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        boolean feishuOk = isFeishuAvailable();
        String feishuLabel = feishuOk ? channels.getFirst().target().label() : "未连接飞书（仅桌面主对话与系统通知）";
        SessionSummaryDto primary = resolvePrimarySession(false);
        String activeModel = resolveActiveModelLabel(s);
        boolean enabled = s.enabled() && s.proactive() && state.rhythm().companionEnabled();
        List<DesktopNotice> unacked = state.desktopNotices().stream().filter(n -> !n.acknowledged()).toList();
        return new CompanionView(
                enabled, primary.id(), primary.title(), activeModel,
                feishuOk, feishuLabel, state.cognition(), state.rhythm(), unacked);
    }

    public CompanionView saveCognition(String owner, SaveCognitionRequest req) {
        if (req == null) throw new IllegalArgumentException("认知内容不能为空");
        CompanionState state = store.load(owner);
        Cognition prev = state.cognition();
        Instant now = automationClock.instant();
        Cognition updated = new Cognition(
                req.backgroundAndPreferences() != null ? req.backgroundAndPreferences() : prev.backgroundAndPreferences(),
                req.routineAndBusyHours() != null ? req.routineAndBusyHours() : prev.routineAndBusyHours(),
                req.activeFocusAndBlockers() != null ? req.activeFocusAndBlockers() : prev.activeFocusAndBlockers(),
                req.pendingHooksAndCuriosities() != null ? req.pendingHooksAndCuriosities() : prev.pendingHooksAndCuriosities(),
                req.busyStartHour() != null ? req.busyStartHour() : prev.busyStartHour(),
                req.busyEndHour() != null ? req.busyEndHour() : prev.busyEndHour(),
                now
        );
        store.save(owner, new CompanionState(updated, state.rhythm(), state.desktopNotices()));
        return publishAndReturn(owner);
    }

    public CompanionView enableCompanion(String owner, EnableCompanionRequest req) {
        if (req == null) throw new IllegalArgumentException("请求不能为空");
        Settings current = repository.settings(owner);
        boolean feishu = req.feishu() != null ? req.feishu() : (isFeishuAvailable() && current.feishu());
        ModelIdentity resolvedModel = resolveConcreteModel(req, current);
        String tz = (req.timezone() == null || req.timezone().isBlank()) ? current.timezone() : req.timezone().strip();

        SaveSettings saveInput = new SaveSettings(
                current.revision(), req.enabled(), req.allSources(),
                resolvedModel.providerId(), resolvedModel.modelName(), tz, 7, 3,
                req.enabled(), feishu, req.enabled() ? 3 : 0,
                req.weatherAlertEnabled() != null ? req.weatherAlertEnabled() : current.weatherSignalEnabled(),
                req.expenseAlertEnabled() != null ? req.expenseAlertEnabled() : current.expenseSignalEnabled(),
                req.serverAlertEnabled() != null ? req.serverAlertEnabled() : current.serverSignalEnabled(),
                req.serverProbeUrl() != null ? req.serverProbeUrl() : current.serverProbeUrl(),
                req.serverProbeName() != null ? req.serverProbeName() : current.serverProbeName(),
                req.mailAlertEnabled() != null ? req.mailAlertEnabled() : current.mailSignalEnabled(),
                req.imapHost() != null ? req.imapHost() : current.imapHost(),
                req.imapPort() != null ? req.imapPort() : current.imapPort(),
                req.imapUsername() != null ? req.imapUsername() : current.imapUsername(),
                req.imapPassword() != null ? req.imapPassword() : "",
                req.mailSenderWhitelist() != null ? req.mailSenderWhitelist() : current.mailSenderWhitelist(),
                req.mailSubjectKeywords() != null ? req.mailSubjectKeywords() : current.mailSubjectKeywords()
        );
        SettingsView saved = authorization.save(owner, saveInput);
        if (req.enabled()) {
            authorization.grant(owner, new Consent(saved.revision(), "life-v1", true));
        }

        CompanionState state = store.load(owner);
        Instant now = automationClock.instant();
        Instant nextReach = req.enabled()
                ? (state.rhythm().nextReachAt() != null && state.rhythm().nextReachAt().isAfter(now)
                    ? state.rhythm().nextReachAt()
                    : LifeCompanionRhythmPolicy.computeNextReachAt(now, ZoneId.of(tz), state.cognition(), 0))
                : null;
        RhythmState rhythm = new RhythmState(
                req.enabled(), state.rhythm().unansweredCount(), false,
                state.rhythm().lastOutreachHook(), state.rhythm().lastLearnedAt(), state.rhythm().lastDataFingerprint(),
                state.rhythm().lastOutreachAt(), state.rhythm().lastUserActiveAt(), nextReach);
        store.save(owner, new CompanionState(state.cognition(), rhythm, state.desktopNotices()));
        if (req.enabled()) {
            return learnNow(owner);
        }
        return publishAndReturn(owner);
    }

    public CompanionView learnNow(String owner) {
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        Instant now = automationClock.instant();
        ZoneId zone = ZoneId.of(s.timezone());

        var material = collector.collect(owner, zone, now);
        Cognition learned = synthesis.synthesizeCognition(s, state.cognition(), material, now);

        RhythmState prevRhythm = state.rhythm();
        Instant latestUserAt = resolveLatestUserActivity(owner, zone, now).orElse(prevRhythm.lastUserActiveAt());
        int unanswered = (latestUserAt != null && prevRhythm.lastOutreachAt() != null
                && latestUserAt.isAfter(prevRhythm.lastOutreachAt())) ? 0 : prevRhythm.unansweredCount();
        boolean busyDeferred = unanswered != 0 && prevRhythm.busyDeferred();
        Instant nextReach = prevRhythm.companionEnabled() && prevRhythm.nextReachAt() == null
                ? LifeCompanionRhythmPolicy.computeNextReachAt(now, zone, learned, unanswered)
                : prevRhythm.nextReachAt();

        RhythmState updatedRhythm = new RhythmState(
                prevRhythm.companionEnabled(), unanswered, busyDeferred, prevRhythm.lastOutreachHook(),
                now, material.fingerprint(), prevRhythm.lastOutreachAt(), latestUserAt, nextReach);
        store.save(owner, new CompanionState(learned, updatedRhythm, state.desktopNotices()));
        return publishAndReturn(owner);
    }

    public CompanionView reachOutNow(String owner, String reasonHint) {
        learnIfDue(owner);
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        Instant now = automationClock.instant();
        ZoneId zone = ZoneId.of(s.timezone());

        String hook = (reasonHint != null && !reasonHint.isBlank())
                ? reasonHint.strip()
                : selectHook(state.cognition(), state.rhythm(), now.atZone(zone));
        String message = synthesis.composeOutreachMessage(s, state.cognition(), state.rhythm(), hook, now.atZone(zone));
        deliverProactiveMessage(owner, s, "COMPANION_OUTREACH", "manual:" + now.toEpochMilli(), message, now);

        CompanionState after = store.load(owner);
        int nextUnanswered = after.rhythm().unansweredCount() + 1;
        Instant nextReach = LifeCompanionRhythmPolicy.computeNextReachAt(now, zone, after.cognition(), nextUnanswered);
        RhythmState updatedRhythm = new RhythmState(
                after.rhythm().companionEnabled(), nextUnanswered, false, hook,
                after.rhythm().lastLearnedAt(), after.rhythm().lastDataFingerprint(),
                now, after.rhythm().lastUserActiveAt(), nextReach);
        store.save(owner, new CompanionState(after.cognition(), updatedRhythm, after.desktopNotices()));
        return publishAndReturn(owner);
    }

    /** 后台生成线程调度入口：严守安静时段（22:00—09:00）、日上限 1 次与忙时顺延规则。 */
    public void tick(String owner) {
        if (resetBarrier != null && resetBarrier.blocked()) return;
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        if (!s.enabled() || !s.proactive() || !state.rhythm().companionEnabled()) return;

        Instant now = automationClock.instant();
        ZoneId zone = ZoneId.of(s.timezone());
        ZonedDateTime local = now.atZone(zone);

        Instant latestUser = resolveLatestUserActivity(owner, zone, now).orElse(state.rhythm().lastUserActiveAt());
        RhythmState rhythm = state.rhythm();
        if (latestUser != null && rhythm.lastOutreachAt() != null && latestUser.isAfter(rhythm.lastOutreachAt())
                && (rhythm.unansweredCount() > 0 || rhythm.busyDeferred())) {
            rhythm = new RhythmState(
                    rhythm.companionEnabled(), 0, false, rhythm.lastOutreachHook(),
                    rhythm.lastLearnedAt(), rhythm.lastDataFingerprint(), rhythm.lastOutreachAt(),
                    latestUser, LifeCompanionRhythmPolicy.computeNextReachAt(now, zone, state.cognition(), 0));
            state = store.save(owner, new CompanionState(state.cognition(), rhythm, state.desktopNotices()));
        }

        learnIfDue(owner);
        state = store.load(owner);
        rhythm = state.rhythm();
        Cognition cog = state.cognition();

        if (LifeCompanionRhythmPolicy.isQuietHours(local)) return;
        Instant dueAt = rhythm.nextReachAt();
        if (dueAt == null) {
            Instant initial = LifeCompanionRhythmPolicy.computeNextReachAt(now, zone, cog, rhythm.unansweredCount());
            store.save(owner, new CompanionState(cog, new RhythmState(
                    rhythm.companionEnabled(), rhythm.unansweredCount(), rhythm.busyDeferred(),
                    rhythm.lastOutreachHook(), rhythm.lastLearnedAt(), rhythm.lastDataFingerprint(),
                    rhythm.lastOutreachAt(), rhythm.lastUserActiveAt(), initial), state.desktopNotices()));
            return;
        }
        if (now.isBefore(dueAt)) return;

        if (LifeCompanionRhythmPolicy.hasReachedDailyOutreachCap(rhythm, local)) {
            Instant nextMorning = LifeCompanionRhythmPolicy.nextAllowedMorning(local);
            store.save(owner, new CompanionState(cog, new RhythmState(
                    rhythm.companionEnabled(), rhythm.unansweredCount(), rhythm.busyDeferred(),
                    rhythm.lastOutreachHook(), rhythm.lastLearnedAt(), rhythm.lastDataFingerprint(),
                    rhythm.lastOutreachAt(), rhythm.lastUserActiveAt(), nextMorning), state.desktopNotices()));
            return;
        }

        if (rhythm.unansweredCount() > 0
                && LifeCompanionRhythmPolicy.isWithinBusyHours(local.getHour(), cog.busyStartHour(), cog.busyEndHour())) {
            Instant deferredTo = LifeCompanionRhythmPolicy.deferAfterBusyHours(local, cog.busyEndHour());
            RhythmState deferredRhythm = new RhythmState(
                    rhythm.companionEnabled(), rhythm.unansweredCount(), true,
                    rhythm.lastOutreachHook(), rhythm.lastLearnedAt(), rhythm.lastDataFingerprint(),
                    rhythm.lastOutreachAt(), rhythm.lastUserActiveAt(), deferredTo);
            store.save(owner, new CompanionState(cog, deferredRhythm, state.desktopNotices()));
            publishSnapshot(owner);
            return;
        }

        String hook = selectHook(cog, rhythm, local);
        String text = synthesis.composeOutreachMessage(s, cog, rhythm, hook, local);
        String dedupKey = "companion:" + local.toLocalDate() + ":" + local.getHour() + ":" + ReviewPolicy.fingerprint(hook);
        deliverProactiveMessage(owner, s, "COMPANION_OUTREACH", dedupKey, text, now);

        CompanionState after = store.load(owner);
        int nextUnanswered = rhythm.unansweredCount() + 1;
        Instant nextReach = LifeCompanionRhythmPolicy.computeNextReachAt(now, zone, cog, nextUnanswered);
        RhythmState nextRhythm = new RhythmState(
                rhythm.companionEnabled(), nextUnanswered, false, hook,
                after.rhythm().lastLearnedAt(), after.rhythm().lastDataFingerprint(),
                now, latestUser, nextReach);
        store.save(owner, new CompanionState(after.cognition(), nextRhythm, after.desktopNotices()));
        publishSnapshot(owner);
    }

    @Override
    public Optional<MemoryRecallPort.Fragment> recallCognition(String owner, String query) {
        if (resetBarrier != null && resetBarrier.blocked()) return Optional.empty();
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        if (!s.enabled() || !s.proactive() || !state.rhythm().companionEnabled()) {
            return Optional.empty();
        }
        Cognition cog = state.cognition();
        String summary = formatCognitionSummary(cog);
        if (summary.isBlank()) return Optional.empty();
        long generation = resetBarrier == null ? -1 : resetBarrier.state().generation();
        Instant version = cog.updatedAt();
        return Optional.of(new MemoryRecallPort.Fragment(
                "主动陪伴四维认知",
                summary,
                () -> {
                    CompanionState current = store.load(owner);
                    Settings latest = repository.settings(owner);
                    return latest.enabled() && latest.proactive() && current.rhythm().companionEnabled()
                            && (resetBarrier == null || resetBarrier.isCurrent(generation))
                            && Objects.equals(version, current.cognition().updatedAt());
                }
        ));
    }

    @Override
    public void onUserTurnRecorded(String ownerId, String sessionId, MemorySourceRegistry.Channel channel) {
        if (ownerId == null || ownerId.isBlank()) return;
        CompanionState state = store.load(ownerId);
        if (!state.rhythm().companionEnabled()) return;
        Settings s = repository.settings(ownerId);
        Instant now = automationClock.instant();
        ZoneId zone = ZoneId.of(s.timezone());
        Instant nextReach = LifeCompanionRhythmPolicy.computeNextReachAt(now, zone, state.cognition(), 0);
        RhythmState resetRhythm = new RhythmState(
                state.rhythm().companionEnabled(), 0, false,
                state.rhythm().lastOutreachHook(), state.rhythm().lastLearnedAt(), state.rhythm().lastDataFingerprint(),
                state.rhythm().lastOutreachAt(), now, nextReach);
        store.save(ownerId, new CompanionState(state.cognition(), resetRhythm, state.desktopNotices()));
        publishSnapshot(ownerId);

        Instant lastLearned = state.rhythm().lastLearnedAt();
        boolean learnDue = lastLearned == null
                || Duration.between(lastLearned, now).compareTo(TURN_RELEARN_COOLDOWN) >= 0;
        if (learnDue && learnScheduled.compareAndSet(false, true)) {
            learnExecutor.submit(() -> {
                try {
                    if (resetBarrier == null || !resetBarrier.blocked()) {
                        learnNow(ownerId);
                    }
                } catch (RuntimeException e) {
                    log.debug("对话后增量认知学习跳过：{}", e.getClass().getSimpleName());
                } finally {
                    learnScheduled.set(false);
                }
            });
        }
    }

    public void syncExternalMessageToPrimarySession(String owner, String kind, String title, String content) {
        if (content == null || content.isBlank()) return;
        Instant now = automationClock.instant();
        String naturalText = LifeCompanionSynthesisService.naturalizeTemplateText(content);
        SessionSummaryDto primary = resolvePrimarySession(false);
        appendToSessionContext(primary.id(), naturalText);
        store.appendDesktopNotice(owner, primary.id(), kind, title, naturalText, now);
        publishSnapshot(owner);
    }

    public void acknowledgeDesktopNotice(String owner, String noticeId) {
        store.acknowledgeDesktopNotice(owner, noticeId);
        publishSnapshot(owner);
    }

    public static String naturalizeTemplateText(String raw) {
        return LifeCompanionSynthesisService.naturalizeTemplateText(raw);
    }

    @PreDestroy
    public void shutdown() {
        learnExecutor.shutdownNow();
    }

    private void learnIfDue(String owner) {
        CompanionState state = store.load(owner);
        Instant now = automationClock.instant();
        if (state.rhythm().lastLearnedAt() == null
                || Duration.between(state.rhythm().lastLearnedAt(), now).compareTo(LEARN_COOLDOWN) >= 0) {
            learnNow(owner);
        }
    }

    private void deliverProactiveMessage(
            String owner, Settings s, String kind, String dedupKey, String text, Instant now
    ) {
        boolean feishuOk = isFeishuAvailable();
        SessionSummaryDto desktopSession = resolvePrimarySession(false);
        appendToSessionContext(desktopSession.id(), text);
        store.appendDesktopNotice(owner, desktopSession.id(), kind, "Agent 主动问候", text, now);
        if (s.enabled() && s.proactive() && s.feishu() && feishuOk) {
            try {
                deliveries.sendNudge(s, "IDLE_CHECKIN", dedupKey, text);
                SessionSummaryDto feishuSession = resolvePrimarySession(true);
                if (!feishuSession.id().equals(desktopSession.id())) {
                    appendToSessionContext(feishuSession.id(), text);
                }
            } catch (RuntimeException e) {
                log.warn("主动问候同步飞书降级：{}", e.getClass().getSimpleName());
            }
        }
    }

    private void appendToSessionContext(String sessionId, String text) {
        try {
            if (agentService != null) {
                agentService.appendProactiveMessageToSession(sessionId, text);
            } else {
                transcripts.appendProactiveAssistantMessage(sessionId, text);
                sessions.touch(sessionId, text);
            }
        } catch (RuntimeException e) {
            log.warn("写入主动消息至对话上下文失败：sessionId={}", sessionId);
        }
    }

    private SessionSummaryDto resolvePrimarySession(boolean feishuChannel) {
        SessionSummaryDto resolved = sessions.resolveOrCreateCompanionSession(feishuChannel);
        if (resolved != null) return resolved;
        return new SessionSummaryDto("companion-default", SessionKind.GENERAL, null, "日常陪伴", "",
                automationClock.instant(), automationClock.instant(), SessionStatus.ACTIVE);
    }

    private boolean isFeishuAvailable() {
        return channels.size() == 1 && channels.getFirst().target().available();
    }

    private ModelIdentity resolveConcreteModel(EnableCompanionRequest req, Settings current) {
        if (modelCatalog == null) {
            throw new IllegalArgumentException("模型目录服务不可用，请先配置模型连接");
        }
        String reqProvider = req.providerId() == null ? "" : req.providerId().strip();
        String reqModel = req.modelName() == null ? "" : req.modelName().strip();
        if (!reqProvider.isBlank() && !"ACTIVE".equalsIgnoreCase(reqProvider)
                && !reqModel.isBlank() && !"ACTIVE".equalsIgnoreCase(reqModel)) {
            return modelCatalog.resolve(reqProvider, reqModel);
        }
        if (current.providerId() != null && !current.providerId().isBlank() && !"ACTIVE".equalsIgnoreCase(current.providerId())
                && current.modelName() != null && !current.modelName().isBlank() && !"ACTIVE".equalsIgnoreCase(current.modelName())) {
            try {
                return modelCatalog.resolve(current.providerId(), current.modelName());
            } catch (IllegalArgumentException ignored) {
                // 已保存模型不可用时解析当前首个启用模型并固化新身份
            }
        }
        return modelCatalog.resolveFirstEnabled();
    }

    private String resolveActiveModelLabel(Settings s) {
        if (modelCatalog != null && s.providerId() != null && !s.providerId().isBlank()
                && s.modelName() != null && !s.modelName().isBlank()) {
            try {
                return modelCatalog.resolve(s.providerId(), s.modelName()).modelName();
            } catch (RuntimeException ignored) {
            }
        }
        return (s.modelName() == null || s.modelName().isBlank()) ? "未配置模型" : s.modelName();
    }

    private Optional<Instant> resolveLatestUserActivity(String owner, ZoneId zone, Instant now) {
        if (activity != null) {
            return activity.lastInteractionAt(owner, zone, now);
        }
        return repository.latestUserActivity(owner);
    }

    private String selectHook(Cognition cog, RhythmState rhythm, ZonedDateTime local) {
        if (rhythm.busyDeferred() || rhythm.unansweredCount() > 0) {
            return "忙完后的温和跟进：" + (cog.pendingHooksAndCuriosities().isBlank()
                    ? "白天看你在忙就没再打扰" : cog.pendingHooksAndCuriosities());
        }
        if (!cog.pendingHooksAndCuriosities().isBlank()) return cog.pendingHooksAndCuriosities();
        if (!cog.activeFocusAndBlockers().isBlank()) return cog.activeFocusAndBlockers();
        if (local.getHour() >= 20) return "晚间顺手问候今天过得怎么样";
        return "日常朋友式简短问候与好奇提问";
    }

    private CompanionView publishAndReturn(String owner) {
        CompanionView snapshot = view(owner);
        if (streamService != null) streamService.publish(owner, snapshot);
        return snapshot;
    }

    private void publishSnapshot(String owner) {
        if (streamService != null) streamService.publish(owner, view(owner));
    }

    private static String formatCognitionSummary(Cognition c) {
        List<String> parts = new ArrayList<>();
        if (!c.backgroundAndPreferences().isBlank()) parts.add("背景与偏好：" + c.backgroundAndPreferences());
        if (!c.routineAndBusyHours().isBlank()) parts.add("作息规律：" + c.routineAndBusyHours());
        if (!c.activeFocusAndBlockers().isBlank()) parts.add("近期主线：" + c.activeFocusAndBlockers());
        if (!c.pendingHooksAndCuriosities().isBlank()) parts.add("待跟进话头：" + c.pendingHooksAndCuriosities());
        return String.join("\n", parts);
    }
}
