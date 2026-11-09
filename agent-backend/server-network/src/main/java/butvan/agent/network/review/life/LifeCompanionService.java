package butvan.agent.network.review.life;

import butvan.agent.agents.agent.AgentService;
import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.review.PersonalDeliveryPort;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionStatus;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.review.life.LifeCompanionModels.*;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import butvan.agent.network.review.service.ReviewGoalService;
import butvan.agent.network.review.service.ReviewModelCatalog;
import butvan.agent.network.review.service.ReviewPolicy;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * 系统性用户认知自动学习与不定时动态主动问候服务。
 */
@Slf4j
@Service
public class LifeCompanionService {
    private static final Duration LEARN_COOLDOWN = Duration.ofMinutes(30);
    private static final Duration BASE_REACH_INTERVAL = Duration.ofHours(6);
    private static final Duration BACKOFF_REACH_INTERVAL = Duration.ofHours(16);

    private final LifeCompanionStore store;
    private final LifeReviewRepository repository;
    private final LifeAuthorizationService authorization;
    private final LifeDeliveryService deliveries;
    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final Clock automationClock;
    private final PersonalContextService personalContext;
    private final RecordService records;
    private final ReviewGoalService goals;
    private final LifeActivityService activity;
    private final AgentService agentService;
    private final ModelHolder modelHolder;
    private final ReviewModelCatalog modelCatalog;
    private final List<PersonalDeliveryPort> channels;

    @Autowired
    public LifeCompanionService(
            LifeCompanionStore store,
            LifeReviewRepository repository,
            LifeAuthorizationService authorization,
            LifeDeliveryService deliveries,
            SessionCatalogService sessions,
            TranscriptService transcripts,
            Clock automationClock,
            @Autowired(required = false) PersonalContextService personalContext,
            @Autowired(required = false) RecordService records,
            @Autowired(required = false) ReviewGoalService goals,
            @Autowired(required = false) LifeActivityService activity,
            @Autowired(required = false) AgentService agentService,
            @Autowired(required = false) ModelHolder modelHolder,
            @Autowired(required = false) ReviewModelCatalog modelCatalog,
            @Autowired(required = false) List<PersonalDeliveryPort> channels
    ) {
        this.store = store;
        this.repository = repository;
        this.authorization = authorization;
        this.deliveries = deliveries;
        this.sessions = sessions;
        this.transcripts = transcripts;
        this.automationClock = automationClock;
        this.personalContext = personalContext;
        this.records = records;
        this.goals = goals;
        this.activity = activity;
        this.agentService = agentService;
        this.modelHolder = modelHolder;
        this.modelCatalog = modelCatalog;
        this.channels = channels == null ? List.of() : channels;
    }

    public CompanionView view(String owner) {
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        boolean feishuOk = isFeishuAvailable();
        String feishuLabel = feishuOk ? channels.getFirst().target().label() : "未连接飞书（仅桌面主对话与系统通知）";
        SessionSummaryDto primary = resolvePrimarySession(feishuOk);
        String activeModel = resolveActiveModelLabel(s);
        boolean enabled = s.enabled() && s.proactive() && state.rhythm().companionEnabled();
        List<DesktopNotice> unacked = state.desktopNotices().stream().filter(n -> !n.acknowledged()).toList();
        return new CompanionView(
                enabled,
                primary.id(),
                primary.title(),
                activeModel,
                feishuOk,
                feishuLabel,
                state.cognition(),
                state.rhythm(),
                unacked
        );
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
        syncToProfileMarkdown(owner, updated);
        return view(owner);
    }

    public CompanionView enableCompanion(String owner, EnableCompanionRequest req) {
        if (req == null) throw new IllegalArgumentException("请求不能为空");
        Settings current = repository.settings(owner);
        boolean feishu = req.feishu() != null ? req.feishu() : (isFeishuAvailable() && current.feishu());
        String providerId = (req.providerId() == null || req.providerId().isBlank()) ? "ACTIVE" : req.providerId().strip();
        String modelName = (req.modelName() == null || req.modelName().isBlank()) ? "ACTIVE" : req.modelName().strip();
        String tz = (req.timezone() == null || req.timezone().isBlank()) ? current.timezone() : req.timezone().strip();

        SaveSettings saveInput = new SaveSettings(
                current.revision(),
                req.enabled(),
                req.allSources(),
                providerId,
                modelName,
                tz,
                7,
                3,
                req.enabled(),
                feishu,
                req.enabled() ? 3 : 0,
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
                    : computeNextReachAt(now, ZoneId.of(tz), state.cognition(), 0, false))
                : null;
        RhythmState rhythm = new RhythmState(
                req.enabled(),
                state.rhythm().unansweredCount(),
                false,
                state.rhythm().lastOutreachHook(),
                state.rhythm().lastLearnedAt(),
                state.rhythm().lastDataFingerprint(),
                state.rhythm().lastOutreachAt(),
                state.rhythm().lastUserActiveAt(),
                nextReach
        );
        store.save(owner, new CompanionState(state.cognition(), rhythm, state.desktopNotices()));
        if (req.enabled()) {
            learnNow(owner);
        }
        return view(owner);
    }

    public CompanionView learnNow(String owner) {
        CompanionState state = store.load(owner);
        Settings s = repository.settings(owner);
        Instant now = automationClock.instant();
        ZoneId zone = ZoneId.of(s.timezone());
        LocalDate today = now.atZone(zone).toLocalDate();

        List<String> recentChats = collectRecentUserMessages(6);
        List<String> recentJournals = collectRecentJournals(owner, today.minusDays(6), today);
        List<String> activeGoalTexts = collectActiveGoals(owner);
        String profileText = personalContext != null ? personalContext.get(owner).content() : "";
        HabitSuggestionView habitSug = activity != null
                ? activity.suggestHabit(owner, s.timezone(), now)
                : null;

        String rawMaterial = String.join("\n",
                "PROFILE:" + profileText,
                "CHATS:" + String.join(" | ", recentChats),
                "JOURNALS:" + String.join(" | ", recentJournals),
                "GOALS:" + String.join(" | ", activeGoalTexts));
        String fingerprint = ReviewPolicy.fingerprint(rawMaterial);

        Cognition learned = synthesizeCognition(
                state.cognition(), profileText, recentChats, recentJournals, activeGoalTexts, habitSug, now);
        RhythmState prevRhythm = state.rhythm();
        Instant latestUserAt = resolveLatestUserActivity(owner, zone, now).orElse(prevRhythm.lastUserActiveAt());
        int unanswered = (latestUserAt != null && prevRhythm.lastOutreachAt() != null
                && latestUserAt.isAfter(prevRhythm.lastOutreachAt())) ? 0 : prevRhythm.unansweredCount();
        boolean busyDeferred = unanswered == 0 ? false : prevRhythm.busyDeferred();
        Instant nextReach = prevRhythm.companionEnabled() && prevRhythm.nextReachAt() == null
                ? computeNextReachAt(now, zone, learned, unanswered, false)
                : prevRhythm.nextReachAt();

        RhythmState updatedRhythm = new RhythmState(
                prevRhythm.companionEnabled(),
                unanswered,
                busyDeferred,
                prevRhythm.lastOutreachHook(),
                now,
                fingerprint,
                prevRhythm.lastOutreachAt(),
                latestUserAt,
                nextReach
        );
        store.save(owner, new CompanionState(learned, updatedRhythm, state.desktopNotices()));
        syncToProfileMarkdown(owner, learned);
        return view(owner);
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
        String message = composeConversationalMessage(state.cognition(), state.rhythm(), hook, now.atZone(zone));
        deliverProactiveMessage(owner, s, "COMPANION_OUTREACH", "manual:" + now.toEpochMilli(), hook, message, now);

        CompanionState after = store.load(owner);
        int nextUnanswered = after.rhythm().unansweredCount() + 1;
        Instant nextReach = computeNextReachAt(now, zone, after.cognition(), nextUnanswered, false);
        RhythmState updatedRhythm = new RhythmState(
                after.rhythm().companionEnabled(),
                nextUnanswered,
                false,
                hook,
                after.rhythm().lastLearnedAt(),
                after.rhythm().lastDataFingerprint(),
                now,
                after.rhythm().lastUserActiveAt(),
                nextReach
        );
        store.save(owner, new CompanionState(after.cognition(), updatedRhythm, after.desktopNotices()));
        return view(owner);
    }

    public void tick(String owner) {
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
                    latestUser, computeNextReachAt(now, zone, state.cognition(), 0, false));
            state = store.save(owner, new CompanionState(state.cognition(), rhythm, state.desktopNotices()));
        }

        learnIfDue(owner);
        state = store.load(owner);
        rhythm = state.rhythm();
        Cognition cog = state.cognition();

        if (local.getHour() < 8 || local.getHour() >= 23) return;
        Instant dueAt = rhythm.nextReachAt();
        if (dueAt == null) {
            Instant initial = computeNextReachAt(now, zone, cog, rhythm.unansweredCount(), false);
            store.save(owner, new CompanionState(cog, new RhythmState(
                    rhythm.companionEnabled(), rhythm.unansweredCount(), rhythm.busyDeferred(),
                    rhythm.lastOutreachHook(), rhythm.lastLearnedAt(), rhythm.lastDataFingerprint(),
                    rhythm.lastOutreachAt(), rhythm.lastUserActiveAt(), initial), state.desktopNotices()));
            return;
        }
        if (now.isBefore(dueAt)) return;

        // 若上次未回复且当前处于用户忙碌时段，不催促，顺延至忙碌时段结束后再自然跟进
        if (rhythm.unansweredCount() > 0 && isWithinBusyHours(local.getHour(), cog.busyStartHour(), cog.busyEndHour())) {
            Instant deferredTo = deferAfterBusyHours(local, cog.busyEndHour());
            RhythmState deferredRhythm = new RhythmState(
                    rhythm.companionEnabled(), rhythm.unansweredCount(), true,
                    rhythm.lastOutreachHook(), rhythm.lastLearnedAt(), rhythm.lastDataFingerprint(),
                    rhythm.lastOutreachAt(), rhythm.lastUserActiveAt(), deferredTo);
            store.save(owner, new CompanionState(cog, deferredRhythm, state.desktopNotices()));
            return;
        }

        String hook = selectHook(cog, rhythm, local);
        String text = composeConversationalMessage(cog, rhythm, hook, local);
        String dedupKey = "companion:" + local.toLocalDate() + ":" + local.getHour() + ":" + ReviewPolicy.fingerprint(hook);
        deliverProactiveMessage(owner, s, "COMPANION_OUTREACH", dedupKey, hook, text, now);

        CompanionState after = store.load(owner);
        int nextUnanswered = rhythm.unansweredCount() + 1;
        Instant nextReach = computeNextReachAt(now, zone, cog, nextUnanswered, false);
        RhythmState nextRhythm = new RhythmState(
                rhythm.companionEnabled(), nextUnanswered, false, hook,
                after.rhythm().lastLearnedAt(), after.rhythm().lastDataFingerprint(),
                now, latestUser, nextReach);
        store.save(owner, new CompanionState(after.cognition(), nextRhythm, after.desktopNotices()));
    }

    public void syncExternalMessageToPrimarySession(String owner, String kind, String title, String content) {
        if (content == null || content.isBlank()) return;
        Instant now = automationClock.instant();
        String naturalText = naturalizeTemplateText(content);
        SessionSummaryDto primary = resolvePrimarySession(isFeishuAvailable());
        appendToSessionContext(primary.id(), naturalText);
        store.appendDesktopNotice(owner, primary.id(), kind, title, naturalText, now);
    }

    public void acknowledgeDesktopNotice(String owner, String noticeId) {
        store.acknowledgeDesktopNotice(owner, noticeId);
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
            String owner, Settings s, String kind, String dedupKey, String hook, String text, Instant now
    ) {
        boolean feishuOk = isFeishuAvailable();
        SessionSummaryDto primary = resolvePrimarySession(feishuOk);
        appendToSessionContext(primary.id(), text);
        store.appendDesktopNotice(owner, primary.id(), kind, "Agent 主动问候", text, now);
        if (s.enabled() && s.proactive() && s.feishu() && feishuOk) {
            try {
                deliveries.sendNudge(s, "IDLE_CHECKIN", dedupKey, text);
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
            log.warn("写入主动消息至主对话上下文失败：sessionId={}", sessionId);
        }
    }

    private SessionSummaryDto resolvePrimarySession(boolean preferFeishu) {
        SessionSummaryDto resolved = sessions.resolveOrCreateCompanionSession(preferFeishu);
        if (resolved != null) return resolved;
        return new SessionSummaryDto("companion-default", SessionKind.GENERAL, null, "日常主对话", "",
                automationClock.instant(), automationClock.instant(), SessionStatus.ACTIVE);
    }

    private boolean isFeishuAvailable() {
        return channels.size() == 1 && channels.getFirst().target().available();
    }

    private String resolveActiveModelLabel(Settings s) {
        if (modelCatalog != null) {
            try {
                var id = modelCatalog.resolveOrActive(s.providerId(), s.modelName());
                return id.modelName();
            } catch (RuntimeException ignored) {
            }
        }
        return (s.modelName() == null || s.modelName().isBlank() || "ACTIVE".equalsIgnoreCase(s.modelName()))
                ? "当前聊天默认模型"
                : s.modelName();
    }

    private List<String> collectRecentUserMessages(int limit) {
        try {
            List<String> items = new ArrayList<>();
            for (var session : sessions.listActive().stream()
                    .filter(s -> s.kind() == SessionKind.GENERAL)
                    .sorted(Comparator.comparing(SessionSummaryDto::updatedAt).reversed())
                    .limit(4)
                    .toList()) {
                for (var msg : transcripts.recent(session.id(), 16)) {
                    if (msg.role() == TranscriptMessageDto.MessageRole.USER
                            && msg.content() != null && !msg.content().isBlank()) {
                        String text = msg.content().strip();
                        items.add(text.substring(0, Math.min(text.length(), 160)));
                        if (items.size() >= limit) return items;
                    }
                }
            }
            return items;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<String> collectRecentJournals(String owner, LocalDate from, LocalDate to) {
        if (records == null) return List.of();
        try {
            return records.journalFragments(owner, from, to).stream()
                    .limit(5)
                    .map(f -> f.recordDate() + ": " + f.text().substring(0, Math.min(f.text().length(), 160)))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<String> collectActiveGoals(String owner) {
        if (goals == null) return List.of();
        try {
            return goals.list(owner).stream()
                    .filter(g -> "ACTIVE".equals(g.state()))
                    .limit(4)
                    .map(g -> g.title() + (g.constraintText().isBlank() ? "" : "（" + g.constraintText() + "）"))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Optional<Instant> resolveLatestUserActivity(String owner, ZoneId zone, Instant now) {
        if (activity != null) {
            return activity.lastInteractionAt(owner, zone, now);
        }
        return repository.latestUserActivity(owner);
    }

    private Cognition synthesizeCognition(
            Cognition current,
            String profileText,
            List<String> chats,
            List<String> journals,
            List<String> activeGoals,
            HabitSuggestionView habitSug,
            Instant now
    ) {
        String bg = current.backgroundAndPreferences();
        if (bg.isBlank() && profileText != null && !profileText.isBlank()) {
            bg = profileText.lines()
                    .filter(l -> !l.startsWith("#") && !l.startsWith("<!--") && !l.isBlank())
                    .limit(3)
                    .reduce((a, b) -> a + "；" + b)
                    .orElse(profileText.substring(0, Math.min(profileText.length(), 160)));
        }
        if (bg.isBlank()) {
            bg = "偏好简洁直接、不打扰的交流方式；注重长期效率与个人成长记录。";
        }

        int busyStart = current.busyStartHour();
        int busyEnd = current.busyEndHour();
        String routine = current.routineAndBusyHours();
        if (habitSug != null && habitSug.available() && habitSug.suggestedHour() != null) {
            int activeHour = habitSug.suggestedHour();
            busyEnd = Math.max(17, Math.min(20, activeHour - 1));
            routine = "通常在 " + String.format("%02d:00—%02d:00", busyStart, busyEnd)
                    + " 集中忙正事，晚间 " + activeHour + ":00 前后互动最活跃。";
        } else if (routine.isBlank()) {
            routine = "工作日白天（约 09:00—18:00）通常在忙，傍晚至晚间较常互动。";
        }

        String focus = current.activeFocusAndBlockers();
        if (!activeGoals.isEmpty() || !journals.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!activeGoals.isEmpty()) parts.add("当前目标：" + String.join("、", activeGoals));
            if (!journals.isEmpty()) parts.add("近期记录：" + journals.getFirst());
            focus = String.join("；", parts);
        } else if (focus.isBlank() && !chats.isEmpty()) {
            focus = "近期关注：" + chats.getFirst();
        }

        String hooks = current.pendingHooksAndCuriosities();
        if (!chats.isEmpty()) {
            hooks = "上次聊到：「" + chats.getFirst() + "」，可顺手跟进后续进展或询问最近忙得怎么样。";
        } else if (!journals.isEmpty()) {
            hooks = "近期手记提到：「" + journals.getFirst() + "」，可自然关心后续感受。";
        } else if (hooks.isBlank()) {
            hooks = "对你的日常节奏还在持续熟悉中，想顺嘴了解你最近最想推进的一件小事。";
        }

        return new Cognition(bg, routine, focus, hooks, busyStart, busyEnd, now);
    }

    private String selectHook(Cognition cog, RhythmState rhythm, ZonedDateTime local) {
        if (rhythm.busyDeferred() || rhythm.unansweredCount() > 0) {
            return "忙完后的温和跟进：" + (cog.pendingHooksAndCuriosities().isBlank()
                    ? "白天看你在忙就没再打扰" : cog.pendingHooksAndCuriosities());
        }
        if (!cog.pendingHooksAndCuriosities().isBlank()) {
            return cog.pendingHooksAndCuriosities();
        }
        if (!cog.activeFocusAndBlockers().isBlank()) {
            return cog.activeFocusAndBlockers();
        }
        if (local.getHour() >= 20) {
            return "晚间顺手问候今天过得怎么样";
        }
        return "日常朋友式简短问候与好奇提问";
    }

    private String composeConversationalMessage(Cognition cog, RhythmState rhythm, String hook, ZonedDateTime local) {
        if (modelHolder != null && modelHolder.isInitialized()) {
            try {
                var model = modelHolder.getModel();
                String prompt = "你是用户的私人智能体朋友。请基于以下对用户的了解，用自然、口语化、像微信/飞书上熟人朋友一样的语气，"
                        + "写一句简短的主动问候或关心（控制在 25 到 75 字之间，不要加任何标题或【提醒】前缀，只输出这一句话）：\n"
                        + "- 背景喜好：" + cog.backgroundAndPreferences() + "\n"
                        + "- 作息忙闲：" + cog.routineAndBusyHours() + "\n"
                        + "- 近期主线：" + cog.activeFocusAndBlockers() + "\n"
                        + "- 本次想聊的话头：" + hook + "\n"
                        + "- 是否刚过忙碌时段且上次未回：" + (rhythm.busyDeferred() || rhythm.unansweredCount() > 0);
                GenerateOptions opts = GenerateOptions.builder()
                        .stream(false).temperature(0.5).maxTokens(180)
                        .additionalBodyParam("thinking", Map.of("type", "disabled"))
                        .build();
                var res = model.stream(List.of(
                        SystemMessage.builder().textContent("只输出一句自然温暖的朋友式口语问候，绝不带报告腔。").build(),
                        UserMessage.builder().textContent(prompt).build()), List.of(), opts)
                        .collectList().block(Duration.ofSeconds(10));
                String text = res == null ? "" : res.stream()
                        .filter(r -> r.getContent() != null)
                        .flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce("", String::concat)
                        .replaceAll("(?s)<think>.*?</think>", "")
                        .strip();
                if (!text.isBlank()) return text;
            } catch (RuntimeException ignored) {
            }
        }

        if (rhythm.busyDeferred() || rhythm.unansweredCount() > 0) {
            if (!cog.activeFocusAndBlockers().isBlank()) {
                return "看你白天一直在忙就没催你，现在忙完歇下来没？" + cleanHookSnippet(cog.activeFocusAndBlockers()) + " 进展还顺吗？";
            }
            return "看你白天估计在忙正事，现在忙完歇口气没？有空随时跟我聊聊近况。";
        }
        if (!cog.pendingHooksAndCuriosities().isBlank()) {
            return "突然想起" + cleanHookSnippet(cog.pendingHooksAndCuriosities()) + "，这两天怎么样啦？有空跟我说说。";
        }
        if (!cog.activeFocusAndBlockers().isBlank()) {
            return "这两天看你在推进「" + cleanHookSnippet(cog.activeFocusAndBlockers()) + "」，进展还顺利吗？累了记得歇会儿。";
        }
        return "今天忙得怎么样？刚顺手整理了下你的近况，有什么想吐槽或者随手记一笔的随时找我。";
    }

    private static String cleanHookSnippet(String raw) {
        if (raw == null || raw.isBlank()) return "上次聊到的事";
        String cleaned = raw.replaceFirst("^(上次聊到：|近期关注：|当前目标：|近期手记提到：)", "").strip();
        return cleaned.length() > 45 ? cleaned.substring(0, 45) + "..." : cleaned;
    }

    public static String naturalizeTemplateText(String raw) {
        if (raw == null) return "";
        return raw.replaceFirst("^【天气变化提醒】", "看了一眼外面的天气，")
                .replaceFirst("^【天气恢复提醒】", "跟你说一声，外面天气好转啦：")
                .replaceFirst("^【支出变化提醒】", "顺手帮你看了一眼今天的账单，")
                .replaceFirst("^【服务器状态提醒】", "刚帮你巡检服务器时发现个小状况：")
                .replaceFirst("^【服务器恢复提醒】", "好消息，刚才那台服务器已经恢复正常啦：")
                .replaceFirst("^【重要邮件提醒】", "刚帮你留意到一封重要邮件：")
                .strip();
    }

    private static boolean isWithinBusyHours(int hour, int start, int end) {
        if (start <= end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    private static Instant deferAfterBusyHours(ZonedDateTime local, int busyEndHour) {
        LocalDate targetDate = local.getHour() >= busyEndHour ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        int targetHour = Math.min(21, Math.max(8, busyEndHour));
        return targetDate.atTime(targetHour, 15).atZone(local.getZone()).toInstant();
    }

    private static Instant computeNextReachAt(
            Instant now, ZoneId zone, Cognition cog, int unansweredCount, boolean immediateAfterBusy
    ) {
        Duration wait = unansweredCount <= 0
                ? BASE_REACH_INTERVAL
                : BACKOFF_REACH_INTERVAL.multipliedBy(Math.min(3, unansweredCount));
        ZonedDateTime candidate = now.plus(wait).atZone(zone);
        if (candidate.getHour() >= 23) {
            candidate = candidate.toLocalDate().plusDays(1).atTime(Math.max(9, cog.busyEndHour()), 10).atZone(zone);
        } else if (candidate.getHour() < 9) {
            candidate = candidate.toLocalDate().atTime(9, 30).atZone(zone);
        }
        if (unansweredCount > 0 && isWithinBusyHours(candidate.getHour(), cog.busyStartHour(), cog.busyEndHour())) {
            return deferAfterBusyHours(candidate, cog.busyEndHour());
        }
        return candidate.toInstant();
    }

    private void syncToProfileMarkdown(String owner, Cognition c) {
        if (personalContext == null) return;
        try {
            String markdown = "## 基本背景与性格喜好\n" + c.backgroundAndPreferences() + "\n\n"
                    + "## 作息规律与忙闲时段\n" + c.routineAndBusyHours()
                    + "（常规忙碌时段：" + String.format("%02d:00—%02d:00", c.busyStartHour(), c.busyEndHour()) + "）\n\n"
                    + "## 近期主线与关注重点\n" + (c.activeFocusAndBlockers().isBlank() ? "暂无" : c.activeFocusAndBlockers()) + "\n\n"
                    + "## 待跟进话头与好奇点\n" + (c.pendingHooksAndCuriosities().isBlank() ? "暂无" : c.pendingHooksAndCuriosities());
            personalContext.save(owner, markdown);
        } catch (RuntimeException e) {
            log.warn("同步系统性认知至 PROFILE.md 降级：{}", e.getClass().getSimpleName());
        }
    }
}
