package butvan.agent.network.review.life;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.review.*;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.usage.*;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.review.life.LifeReviewModels.*;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import butvan.agent.network.review.model.ReviewModels.ModelIdentity;
import butvan.agent.network.review.service.ReviewModelCatalog;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 临时 SQLite + 假模型／渠道验证版本、跨夜、撤权、重启和重置，不接触真实用户数据。 */
@SpringBootTest(classes = LifeReviewIntegrationTest.Application.class)
@AutoConfigureMockMvc
class LifeReviewIntegrationTest {
    private static final Path ROOT = temporary();
    private static final Instant NOW = Instant.parse("2026-10-30T13:00:00Z");
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);
    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({LocalDatabaseConfiguration.class, LifeReviewRepository.class, LifeAuthorizationService.class,
            LifeReviewService.class, LifeOutreachService.class, LifeDeliveryService.class, LifeReplyService.class,
            LifeActivityService.class, LifeSignalMonitorService.class, LifeResetParticipant.class,
            LifeReviewController.class, ApiExceptionHandler.class,
            butvan.agent.network.reset.service.ResetOperationAspect.class})
    static class Application {
        @Bean Clock automationClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId z) { return this; }
                public Instant instant() { return TIME.get(); }
            };
        }
        @Bean ResetBarrier barrier() { return new ResetBarrier(new AgentStorageProperties(ROOT.resolve("agent"))); }
        @Bean TokenCounter counter() { return new ApproximateTokenCounter(); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { r.add("butvan.database.path", () -> ROOT.resolve("test.db").toString()); }
    @MockitoBean ReviewModelCatalog models;
    @MockitoBean ReviewGenerator generator;
    @MockitoBean LifeMaterialService materials;
    @MockitoBean LifePersonalMaterialService personal;
    @MockitoBean PersonalDeliveryPort channel;
    @MockitoBean butvan.agent.agents.identity.CurrentUserProvider user;
    @MockitoBean butvan.agent.agents.session.SessionCatalogService sessions;
    @MockitoBean butvan.agent.agents.session.TranscriptService transcripts;
    @MockitoBean butvan.agent.network.record.service.RecordService records;
    @MockitoBean butvan.agent.network.dailycontext.service.DailyContextService dailyContext;
    @MockitoBean butvan.agent.network.daily.service.ExpenseAnalyticsService expenseAnalytics;
    @jakarta.annotation.Resource LifeAuthorizationService authorization;
    @jakarta.annotation.Resource LifeReviewService reviews;
    @jakarta.annotation.Resource LifeReviewRepository repository;
    @jakarta.annotation.Resource LifeDeliveryService delivery;
    @jakarta.annotation.Resource LifeOutreachService outreach;
    @jakarta.annotation.Resource LifeReplyService replies;
    @jakarta.annotation.Resource LifeResetParticipant reset;
    @jakarta.annotation.Resource ResetBarrier barrier;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource MockMvc mvc;
    @BeforeEach void setup() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        TIME.set(NOW);
        if (barrier.blocked()) barrier.complete("测试清理完成");
        repository.reset("owner"); repository.reset("other");
        jdbc.update("DELETE FROM life_review_budget"); jdbc.update("DELETE FROM life_review_archive");
        when(user.currentUserId()).thenReturn("owner");
        when(sessions.listActive()).thenReturn(List.of());
        when(records.journalFragments(anyString(), any(), any())).thenReturn(List.of());
        when(personal.collect(any(), any())).thenReturn(List.of());
        when(models.resolve("p", "m")).thenReturn(new ModelIdentity("p", "m", "openai", "fingerprint"));
        when(channel.target()).thenReturn(new PersonalDeliveryPort.Target(true, "target", "本人飞书"));
        when(channel.send(anyString(), anyString(), anyString())).thenReturn(new PersonalDeliveryPort.Attempt("SENT", "message"));
        when(materials.collect(any(), any(), any())).thenReturn(new LifeMaterialService.Collected(
                List.of(new Material("journal-1", "JOURNAL", "r1", DAY.toString(), "今天尝试把自己的手记译成英文，感觉不错。", false)), List.of(), "仅有限材料；原始归属日与截止点可追溯"));
        when(generator.generate(any())).thenReturn(new ReviewDraft(1, "只有有限材料", List.of(
                new ReviewDraft.Observation("life.try", "FACT_OBSERVATION", "你尝试了用手记学英语，并记录当时感觉不错。", List.of("journal-1"), List.of(), "一次尝试不代表长期效果")), List.of(), List.of()));
    }
    private SettingsView authorize(boolean proactive, boolean feishu) {
        var saved = authorization.save("owner", new SaveSettings(repository.settings("owner").revision(), true, Set.of("JOURNAL"), "p", "m", "Asia/Shanghai", 7, 3, proactive, feishu));
        return authorization.grant("owner", new Consent(saved.revision(), "life-v1", true));
    }
    private ContentView ready(boolean proactive, boolean feishu) {
        authorize(proactive, feishu);
        var c = reviews.prepare("owner", new Prepare(DAY, 0, false)); reviews.tick();
        return reviews.get("owner", c.id());
    }
    @Test void defaultClosedAndSavingDoesNotGrant() {
        assertFalse(authorization.view("owner").enabled());
        var s = authorization.save("owner", new SaveSettings(0, true, Set.of("JOURNAL"), "p", "m", "Asia/Shanghai", 7, 1, false, false));
        assertFalse(s.authorized());
        assertThrows(IllegalArgumentException.class, () -> reviews.prepare("owner", new Prepare(DAY, 0, false)));
        verifyNoInteractions(generator, materials);
    }
    @Test void httpReadsAreOwnerScopedAndDoNotCallModel() throws Exception {
        mvc.perform(get("/agent/life-reviews/settings")).andExpect(jsonPath("$.data.authorized").value(false));
        mvc.perform(get("/agent/life-reviews/archives")).andExpect(jsonPath("$.data").isArray());
        verifyNoInteractions(generator, materials);
    }
    @Test void preparedVersionHasSourcesAndReadingNeverRegenerates() {
        var c = ready(false, false);
        assertEquals("READY", c.state()); assertEquals(1, c.materials().size());
        assertEquals(c.id(), reviews.prepare("owner", new Prepare(DAY, 0, false)).id());
        reviews.history("owner"); reviews.get("owner", c.id()); reviews.tick();
        verify(generator, times(1)).generate(any());
        assertThrows(IllegalArgumentException.class, () -> reviews.get("other", c.id()));
    }
    @Test void supplementCreatesNewVersionAndPreservesSentCopy() {
        var c = ready(false, true); delivery.send("owner", c.id(), false);
        replies.reply("owner", c.id(), "reply-1", "补充：老师的观点只是转述，不是我的结论。");
        assertEquals(2, repository.latest("owner", DAY).orElseThrow().version());
        assertEquals(1, reviews.get("owner", c.id()).version());
        reviews.tick();
        var newest = repository.latest("owner", DAY).orElseThrow();
        assertTrue(newest.materials().stream().anyMatch(m -> m.kind().equals("SUPPLEMENT")));
        assertEquals("SENT", repository.deliveries("owner", c.id()).stream().filter(d -> d.channel().equals("FEISHU")).findFirst().orElseThrow().state());
        assertEquals(c.themeId(), reviews.get("owner", newest.id()).themeId());
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
    }
    @Test void reauthorizationDoesNotReuseOldGrantAndModelIdentityCannotSilentlyChange() {
        var c = ready(false, false);
        when(models.resolve("p", "m")).thenReturn(new ModelIdentity("p", "m", "openai", "changed"));
        assertThrows(IllegalArgumentException.class, () -> reviews.prepare("owner", new Prepare(DAY, 1, true)));
        assertEquals("READY", reviews.get("owner", c.id()).state());
    }
    @Test void revocationDuringModelCallDiscardsLateResult() {
        var s = authorize(false, false);
        var c = reviews.prepare("owner", new Prepare(DAY, 0, false));
        when(generator.generate(any())).thenAnswer(call -> {
            authorization.revoke("owner", new Consent(s.revision(), "life-v1", true));
            return new ReviewDraft(1, "", List.of(), List.of(), List.of());
        });
        reviews.tick();
        assertEquals("SKIPPED", reviews.get("owner", c.id()).state());
        assertTrue(reviews.get("owner", c.id()).materials().isEmpty());
    }
    @Test void missingMaterialsAreNotInventedAndQuestionsCanBeSkipped() {
        when(materials.collect(any(), any(), any())).thenReturn(new LifeMaterialService.Collected(List.of(), List.of(), "没有可用记录，不代表无事发生"));
        var c = ready(false, false);
        assertEquals("DEGRADED", c.state()); assertEquals(1, c.questions().size());
        verifyNoInteractions(generator);
    }
    @Test void after2330DefersUntilNextNoonAndNeverSendsInMorning() {
        TIME.set(Instant.parse("2026-10-30T15:45:00Z"));
        var c = ready(true, true);
        assertEquals(Instant.parse("2026-10-31T04:00:00Z"), c.availableAt());
        outreach.tick("owner");
        TIME.set(Instant.parse("2026-10-31T02:00:00Z")); outreach.tick("owner");
        verify(channel, never()).send(anyString(), anyString(), anyString());
        TIME.set(Instant.parse("2026-10-31T04:00:00Z")); outreach.tick("owner");
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
    }
    @Test void unknownDeliveryNeverAutomaticallyOrManuallyRetries() {
        var c = ready(true, true);
        when(channel.send(anyString(), anyString(), anyString())).thenReturn(new PersonalDeliveryPort.Attempt("UNKNOWN", ""));
        outreach.tick("owner"); outreach.tick("owner");
        assertThrows(IllegalArgumentException.class, () -> delivery.send("owner", c.id(), true));
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
    }
    @Test void knownFailureRetriesSameFrozenContentAndStableId() {
        var c = ready(false, true);
        when(channel.send(anyString(), anyString(), anyString())).thenReturn(new PersonalDeliveryPort.Attempt("FAILED", ""));
        delivery.send("owner", c.id(), false); delivery.send("owner", c.id(), true);
        var ids = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(channel, times(2)).send(eq("target"), ids.capture(), anyString());
        assertEquals(ids.getAllValues().getFirst(), ids.getAllValues().getLast());
        verify(generator, times(1)).generate(any());
    }
    @Test void restartTurnsInFlightWorkIntoUnknownWithoutReplay() {
        authorize(false, false); var c = reviews.prepare("owner", new Prepare(DAY, 0, false));
        var internal = repository.content("owner", c.id()).orElseThrow();
        repository.finish(internal.finish("RUNNING", null, ""), "PENDING"); repository.recover(); reviews.tick();
        assertEquals("UNKNOWN", reviews.get("owner", c.id()).state()); verifyNoInteractions(generator);
    }
    @Test void correctingHabitChangesPendingTimeAndNoReplyDoesNotCreateAnotherCandidate() {
        var c = ready(true, true);
        outreach.habit("owner", new SaveHabit(0, 22, "我明确说明最近22点后才有空", true));
        outreach.tick("owner"); verify(channel, never()).send(anyString(), anyString(), anyString());
        var updated = outreach.habit("owner", new SaveHabit(1, 20, "", true));
        assertTrue(updated.source().contains("20:00"));
        outreach.tick("owner"); outreach.propose("owner", c.id()); outreach.tick("owner");
        assertEquals(1, outreach.candidates("owner").size());
        assertEquals("PRESENTED", outreach.candidates("owner").getFirst().state());
        assertEquals("", outreach.candidates("owner").getFirst().feedback());
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
    }
    @Test void cancellationAndExpiryPreventPresentation() {
        ready(true, true); var c = outreach.candidates("owner").getFirst();
        outreach.feedback("owner", c.id(), new Feedback("CANCEL", "")); outreach.tick("owner");
        assertEquals("CANCELLED", outreach.candidates("owner").getFirst().state());
        verify(channel, never()).send(anyString(), anyString(), anyString());
    }
    @Test void resetClearsDerivedStateButKeepsFrozenOutboundCopyAndBudgets() {
        var c = ready(false, true); delivery.send("owner", c.id(), false);
        reset.clean("owner");
        assertTrue(reviews.history("owner").isEmpty()); assertFalse(authorization.view("owner").authorized());
        assertEquals(1, repository.archives("owner").size());
        assertEquals(1, jdbc.queryForObject("SELECT calls FROM life_review_budget WHERE owner_id='owner'", Integer.class));
    }
    @Test void resetGenerationRejectsPendingOldVersion() {
        authorize(false, false); var c = reviews.prepare("owner", new Prepare(DAY, 0, false));
        barrier.begin("reset", "test", "owner"); barrier.complete("测试代次已变化"); reviews.tick();
        assertEquals("SKIPPED", reviews.get("owner", c.id()).state()); verifyNoInteractions(generator, materials);
    }
    @Test void deletedMaterialCannotBePublishedEvenAsDegradedFallback() {
        authorize(false, false); var c = reviews.prepare("owner", new Prepare(DAY, 0, false));
        doThrow(new IllegalArgumentException("来源已删除")).when(materials).verify(any(), anyList());
        reviews.tick();
        assertEquals("SKIPPED", reviews.get("owner", c.id()).state());
        assertTrue(reviews.get("owner", c.id()).materials().isEmpty());
    }
    @Test void inputBudgetActuallyRemovesExcessMaterialsBeforeCallingModel() throws Exception {
        when(materials.collect(any(), any(), any())).thenReturn(new LifeMaterialService.Collected(
                java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> new Material("journal-" + i,
                    "JOURNAL", "r" + i, DAY.toString(), "记".repeat(600), false)).toList(), List.of(), "有界材料"));
        ready(false, false);
        var captor = org.mockito.ArgumentCaptor.forClass(ReviewGenerationRequest.class);
        verify(generator).generate(captor.capture());
        assertTrue(captor.getValue().narratives().size() < 20);
        assertTrue(new ApproximateTokenCounter().count(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(captor.getValue())) <= 4000);
        assertTrue(captor.getValue().coverage().contains("预算截断"));
    }
    @Test void noNewInformationAndNoReplyNeverDefeatsGlobalCooldown() {
        ready(true, true);
        when(materials.collect(any(), eq(DAY.minusDays(1)), any())).thenReturn(new LifeMaterialService.Collected(
                List.of(new Material("journal-yesterday", "JOURNAL", "r2", DAY.minusDays(1).toString(), "昨天的另一件虚构经历", false)), List.of(), "昨天有限材料"));
        // 第二份复盘也须引用自身证据，不能依赖降级原文投递来验证冷却。
        when(generator.generate(any())).thenReturn(new ReviewDraft(1, "昨天有限材料", List.of(
                new ReviewDraft.Observation("life.other", "FACT_OBSERVATION", "昨天记录了另一件经历。",
                        List.of("journal-yesterday"), List.of(), "有限材料")), List.of(), List.of()));
        reviews.prepare("owner", new Prepare(DAY.minusDays(1), 0, false)); reviews.tick();
        outreach.tick("owner"); outreach.tick("owner");
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
        assertEquals(1, outreach.candidates("owner").stream().filter(c -> c.state().equals("PENDING")).count());
        TIME.set(NOW.plus(Duration.ofHours(37))); outreach.tick("owner");
        assertTrue(outreach.candidates("owner").stream().noneMatch(c -> c.state().equals("PENDING")));
    }
    @Test void replyIdCannotBeReusedForDifferentTextAndDuplicateReplyDoesNotCreateAnotherVersion() {
        var c = ready(false, false);
        replies.reply("owner", c.id(), "same-reply", "虚构补充");
        replies.reply("owner", c.id(), "same-reply", "虚构补充");
        assertEquals(2, repository.latest("owner", DAY).orElseThrow().version());
        assertThrows(IllegalArgumentException.class, () -> replies.reply("owner", c.id(), "same-reply", "另一个补充"));
    }
    @Test void newGrantCreatesANewVersionRatherThanRevivingOldAuthorization() {
        var c = ready(false, false); authorize(false, false);
        var next = reviews.prepare("owner", new Prepare(DAY, 0, false));
        assertEquals(2, next.version()); assertNotEquals(c.id(), next.id());
        assertNotEquals(repository.content("owner", c.id()).orElseThrow().grantId(), repository.content("owner", next.id()).orElseThrow().grantId());
    }
    @Test void modelQuotaFailurePreservesMaterialsWithoutAnotherCall() {
        var saved = authorization.save("owner", new SaveSettings(0, true, Set.of("JOURNAL"), "p", "m", "Asia/Shanghai", 7, 1, false, false));
        authorization.grant("owner", new Consent(saved.revision(), "life-v1", true));
        var c = reviews.prepare("owner", new Prepare(DAY, 0, false)); reviews.tick();
        replies.reply("owner", c.id(), "quota", "虚构补充"); reviews.tick();
        assertEquals("DEGRADED", repository.latest("owner", DAY).orElseThrow().state());
        assertTrue(repository.latest("owner", DAY).orElseThrow().message().contains("额度"));
        verify(generator, times(1)).generate(any());
    }
    @Test void emptyReportDoesNotCreateProactiveNoise() {
        when(materials.collect(any(), any(), any())).thenReturn(new LifeMaterialService.Collected(List.of(), List.of(), "无可用记录"));
        ready(true, true); outreach.tick("owner");
        assertTrue(outreach.candidates("owner").isEmpty());
        verify(channel, never()).send(anyString(), anyString(), anyString());
    }
    @Test void failedGenerationKeepsMaterialsLocallyAndCannotDeliverRawRecords() {
        when(generator.generate(any())).thenThrow(new ReviewGenerator.ReviewGenerationException(
                ReviewGenerator.ReviewGenerationException.Kind.TIMEOUT, "secret-provider-response"));
        var c = ready(true, true);
        assertEquals("DEGRADED", c.state());
        assertFalse(c.materials().isEmpty());
        assertTrue(c.message().contains("超时"));
        assertFalse(c.message().contains("secret-provider-response"));
        assertTrue(outreach.candidates("owner").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> delivery.send("owner", c.id(), false));
        assertThrows(IllegalArgumentException.class, () -> outreach.event("owner",
                new EventCandidate("失败事件", c.id(), "journal-1", "EVENT")));
        repository.candidate(new Candidate("legacy-failed", "owner", "旧失败版本", "legacy", c.id(), c.id(),
                "PENDING", NOW, NOW.plusSeconds(3600), NOW, null, ""));
        outreach.tick("owner");
        assertTrue(outreach.candidates("owner").stream().anyMatch(v -> v.id().equals("legacy-failed") && v.state().equals("CANCELLED")));
        verify(channel, never()).send(anyString(), anyString(), anyString());
        assertFalse(LifeDeliveryService.render(repository.latest("owner", DAY).orElseThrow()).contains("原始记录："));
        reset(generator);
        when(generator.generate(any())).thenReturn(new ReviewDraft(1, "重试成功", List.of(
                new ReviewDraft.Observation("life.retry", "FACT_OBSERVATION", "重试后成功生成手记回顾。",
                        List.of("journal-1"), List.of(), "单次记录")), List.of(), List.of()));
        var retried = reviews.prepare("owner", new Prepare(DAY, 0, false));
        assertEquals(2, retried.version());
        assertEquals("PENDING", retried.state());
        reviews.tick();
        assertEquals("READY", reviews.get("owner", retried.id()).state());
    }
    @Test void httpWritesRequireObservedResetGenerationBeforePreparingContent() throws Exception {
        authorize(false, false);
        String body = "{\"day\":\"2026-10-30\",\"expectedVersion\":0,\"supplement\":false}";
        mvc.perform(post("/agent/life-reviews/prepare").contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(428));
        mvc.perform(post("/agent/life-reviews/prepare").header("X-Agent-Generation", barrier.state().generation())
                .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.state").value("PENDING"));
        verifyNoInteractions(generator);
    }
    @Test void manualEventDeduplicatesEvidenceAndRecoveryRequiresPresentedEvent() {
        var first = ready(true, true);
        var daily = outreach.candidates("owner").getFirst();
        outreach.feedback("owner", daily.id(), new Feedback("CANCEL", "不主动重复日报"));
        assertThrows(IllegalArgumentException.class, () -> outreach.event("owner", new EventCandidate("学习状态", first.id(), "journal-1", "RECOVERY")));
        assertThrows(IllegalArgumentException.class, () -> outreach.event("owner", new EventCandidate("学习状态", first.id(), "unknown", "EVENT")));
        var event = new EventCandidate("学习状态", first.id(), "journal-1", "EVENT");
        outreach.event("owner", event); outreach.event("owner", event);
        assertEquals(1, outreach.candidates("owner").stream().filter(c -> c.topic().equals("学习状态")).count());
        outreach.tick("owner");
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
        assertFalse(LifeDeliveryService.render(repository.content("owner", first.id()).orElseThrow()).contains("journal-1"));
        TIME.set(NOW.plus(Duration.ofHours(25)));
        when(materials.collect(any(), any(), any())).thenReturn(new LifeMaterialService.Collected(
                List.of(new Material("journal-1", "JOURNAL", "r1", DAY.toString(), "补充：原来的限制已经解除，学习恢复了。", false)), List.of(), "恢复来源可追溯"));
        var next = reviews.prepare("owner", new Prepare(DAY, 1, true)); reviews.tick();
        delivery.send("owner", next.id(), false);
        verify(channel, times(2)).send(anyString(), anyString(), anyString());
        outreach.event("owner", new EventCandidate("学习状态", next.id(), "journal-1", "RECOVERY"));
        outreach.tick("owner");
        verify(channel, times(3)).send(anyString(), anyString(), anyString());
        assertEquals(1, outreach.candidates("owner").stream().filter(c -> c.eventKind().equals("RECOVERY") && c.state().equals("PRESENTED")).count());
    }
    @Test void backlogLimitAndCooldownReadFullLedgerInsteadOfUiPage() {
        var content = ready(true, true); outreach.tick("owner");
        for (int i = 0; i < 100; i++) repository.candidate(new Candidate("backlog-" + i, "owner", "topic-" + i,
                "new", content.id(), content.id(), "PENDING", NOW, NOW.plus(Duration.ofHours(36)), NOW.plusSeconds(i + 1), null, ""));
        TIME.set(NOW.plusSeconds(120));
        assertEquals(100, repository.pendingCount("owner"));
        assertTrue(repository.candidates("owner").stream().allMatch(c -> c.sentAt() == null));
        assertThrows(IllegalArgumentException.class, () -> outreach.event("owner", new EventCandidate("另一个主题", content.id(), "journal-1", "EVENT")));
        outreach.tick("owner");
        verify(channel, times(1)).send(anyString(), anyString(), anyString());
    }
    @Test void priorDayObservationsAndActionsArePassedToNextDayAndShortcutReplyTargetsLatest() {
        authorize(false, false);
        when(generator.generate(any())).thenReturn(new ReviewDraft(1, "首日材料", List.of(
                new ReviewDraft.Observation("study.try", "FACT_OBSERVATION", "你尝试了手记翻译。", List.of("journal-1"), List.of(), "首次尝试")),
                List.of(),
                List.of(new ReviewDraft.Action("study.try", null, "small-start-v1", "明天先翻两句手记", "降低启动门槛", List.of("journal-1"), 7, "完成两句翻译"))));
        var prev = reviews.prepare("owner", new Prepare(DAY.minusDays(1), 0, false));
        reviews.tick();
        assertEquals("READY", reviews.get("owner", prev.id()).state());

        var captor = org.mockito.ArgumentCaptor.forClass(ReviewGenerationRequest.class);
        reset(generator);
        when(generator.generate(captor.capture())).thenAnswer(inv -> {
            ReviewGenerationRequest req = inv.getArgument(0);
            String actId = req.historyActions().getFirst().id();
            return new ReviewDraft(1, "次日材料",
                    List.of(new ReviewDraft.Observation("study.try", "FACT_OBSERVATION", "今天继续了手记翻译。", List.of("journal-1"), List.of(), "连续两天")),
                    List.of(new ReviewDraft.FollowUp(actId, "已跟进昨天的两句翻译建议。", List.of("journal-1"))),
                    List.of());
        });
        var today = reviews.prepare("owner", new Prepare(DAY, 0, false));
        reviews.tick();
        assertEquals(1, captor.getValue().historyActions().size());
        assertEquals("明天先翻两句手记", captor.getValue().historyActions().getFirst().title());
        assertEquals(1, captor.getValue().observations().size());
        var todayView = reviews.get("owner", today.id());
        assertTrue(todayView.items().stream().anyMatch(i -> "FOLLOW_UP".equals(i.kind()) && i.text().contains("已跟进昨天")));

        replies.reply("owner", "latest", "shortcut-reply-1", "免ID直接补充今日经历");
        assertEquals(2, repository.latest("owner", DAY).orElseThrow().version());
    }
    @Test void chatContextPortRecallsRecentReviewOnlyWhenRelevantAndAuthorized() {
        var granted = authorize(false, false);
        when(generator.generate(any())).thenReturn(new ReviewDraft(1, "今日材料", List.of(
                new ReviewDraft.Observation("study.try", "FACT_OBSERVATION", "你尝试了手记翻译。", List.of("journal-1"), List.of(), "首次尝试")),
                List.of(),
                List.of(new ReviewDraft.Action("study.try", null, "small-start-v1", "明天先翻两句手记", "降低启动门槛", List.of("journal-1"), 7, "完成两句翻译"))));
        reviews.prepare("owner", new Prepare(DAY, 0, false));
        reviews.tick();

        assertTrue(reviews.recallRecentReview("owner", "今天天气怎么样").isEmpty());
        var fragment = reviews.recallRecentReview("owner", "刚才复盘里那个学习建议，我下一步怎么调整？").orElseThrow();
        assertTrue(fragment.content().contains("明天先翻两句手记"));
        assertTrue(fragment.stillCurrent().getAsBoolean());

        authorization.revoke("owner", new Consent(granted.revision(), "life-v1", true));
        assertFalse(fragment.stillCurrent().getAsBoolean());
        assertTrue(reviews.recallRecentReview("owner", "刚才复盘里那个学习建议").isEmpty());
    }
    private butvan.agent.agents.session.dto.TranscriptMessageDto userMsg(String id, Instant at) {
        return new butvan.agent.agents.session.dto.TranscriptMessageDto(id, "turn",
                butvan.agent.agents.session.dto.TranscriptMessageDto.MessageRole.USER, "x", at,
                butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus.COMPLETED, null, List.of(), null, null);
    }
    @Test void habitSuggestionObservesUserMessageTimestampsAndIdleCheckinSendsOncePerSilenceWindow() throws Exception {
        // 1. 仅 1 天 1 条消息时不满足建议门槛
        var s1 = new butvan.agent.agents.session.dto.SessionSummaryDto("s1",
                butvan.agent.agents.session.dto.SessionKind.GENERAL, null, "日常对话", "", NOW, NOW,
                butvan.agent.agents.session.dto.SessionStatus.ACTIVE);
        when(sessions.listActive()).thenReturn(List.of(s1));
        when(transcripts.recent("s1", 48)).thenReturn(List.of(userMsg("m1", NOW.minus(Duration.ofHours(1)))));
        assertFalse(outreach.habitSuggestion("owner").available());

        // 2. 跨 3 个活跃日、共 5 条 21:00 (Asia/Shanghai = 13:00Z) 用户消息时生成建议 21 点，且不自动写入作息表
        when(transcripts.recent("s1", 48)).thenReturn(List.of(
                userMsg("m1", NOW.minus(Duration.ofDays(3))),
                userMsg("m2", NOW.minus(Duration.ofDays(3)).plusSeconds(60)),
                userMsg("m3", NOW.minus(Duration.ofDays(2))),
                userMsg("m4", NOW.minus(Duration.ofDays(1))),
                userMsg("m5", NOW.minus(Duration.ofDays(1)).plusSeconds(120))));
        var suggestion = outreach.habitSuggestion("owner");
        assertTrue(suggestion.available());
        assertEquals(21, suggestion.suggestedHour());
        assertNull(outreach.habit("owner"));

        // 3. 开启 3 天久未互动问候并授权；当最近互动在 1 天前时不触发问候
        var saved = authorization.save("owner", new SaveSettings(repository.settings("owner").revision(),
                true, Set.of("JOURNAL"), "p", "m", "Asia/Shanghai", 7, 3, true, true, 3));
        authorization.grant("owner", new Consent(saved.revision(), "life-v1", true));
        outreach.tick("owner");
        verify(channel, never()).send(anyString(), anyString(), anyString());

        // 4. 推进时间到授权与最近互动后满 3 天（NOW + 3 天），触发一次固定问候且不调用模型
        TIME.set(NOW.plus(Duration.ofDays(3)));
        outreach.tick("owner");
        verify(channel, times(1)).send(eq("target"), anyString(), contains("好几天没见你来记录或聊近况了"));
        verifyNoInteractions(generator);
        assertEquals(1, outreach.nudges("owner").size());
        String nudgeId = outreach.nudges("owner").getFirst().id();
        assertEquals("SENT", outreach.nudges("owner").getFirst().state());

        // 5. 未回复时再过 2 天（同一沉默窗口），不会重复催促
        TIME.set(NOW.plus(Duration.ofDays(5)));
        outreach.tick("owner");
        verify(channel, times(1)).send(anyString(), anyString(), anyString());

        // 6. 可通过 HTTP 提交问候反馈
        mvc.perform(post("/agent/life-reviews/nudges/" + nudgeId + "/feedback")
                .header("X-Agent-Generation", barrier.state().generation())
                .contentType("application/json").content("{\"decision\":\"HELPFUL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].feedback").value("HELPFUL"));
    }
    @Test void weatherAndExpenseSignalsDeliverDeterministicNudgesWithRecoveryAndDoNotBlockDailyReview() {
        // 1. 支出提醒未勾选 FINANCE 来源时拒绝保存
        assertThrows(IllegalArgumentException.class, () -> authorization.save("owner",
                new SaveSettings(0, true, Set.of("JOURNAL"), "p", "m", "Asia/Shanghai", 7, 3, true, true, 0, true, true)));

        // 2. 勾选 JOURNAL + FINANCE 并开启天气与支出提醒后授权
        var saved = authorization.save("owner", new SaveSettings(
                0, true, Set.of("JOURNAL", "FINANCE"), "p", "m", "Asia/Shanghai", 7, 3, true, true, 0, true, true));
        authorization.grant("owner", new Consent(saved.revision(), "life-v1", true));

        // 3. 早上 09:00 (01:00Z)：首次观测正常晴天 24°C，仅记录基线，不发送打扰消息
        TIME.set(Instant.parse("2026-10-30T01:00:00Z"));
        when(dailyContext.getSummary(eq(DAY), eq(true), eq(false))).thenReturn(
                new butvan.agent.network.dailycontext.dto.DailyContextDtos.SummaryResponse(
                        DAY,
                        new butvan.agent.network.dailycontext.dto.DailyContextDtos.WeatherResponse(
                                "杭州", "晴", "100", 24.0, "°C", 24.0, 50, "东风", 2.0, "m/s", ""),
                        null, null, null));
        outreach.tick("owner");
        verify(channel, never()).send(anyString(), anyString(), anyString());

        // 4. 早上 10:00 (02:00Z)：天气转为暴雨 18°C，触发一次 WEATHER_ALERT，重复 tick 不重复发送
        TIME.set(Instant.parse("2026-10-30T02:00:00Z"));
        when(dailyContext.getSummary(eq(DAY), eq(true), eq(false))).thenReturn(
                new butvan.agent.network.dailycontext.dto.DailyContextDtos.SummaryResponse(
                        DAY,
                        new butvan.agent.network.dailycontext.dto.DailyContextDtos.WeatherResponse(
                                "杭州", "暴雨", "307", 18.0, "°C", 17.0, 90, "北风", 6.0, "m/s", ""),
                        null, null, null));
        outreach.tick("owner");
        outreach.tick("owner");
        verify(channel, times(1)).send(eq("target"), anyString(), contains("【天气变化提醒】杭州当前转为暴雨"));

        // 5. 下午 14:00 (06:00Z)：天气转晴 22°C，触发一次 WEATHER_RECOVERY 恢复通知；
        //    同时今日 CNY 支出 360 元（高于前 4 个活跃日均 50 元的 2 倍且 ≥ 200 元），触发一次 EXPENSE_ALERT
        TIME.set(Instant.parse("2026-10-30T06:00:00Z"));
        when(dailyContext.getSummary(eq(DAY), eq(true), eq(false))).thenReturn(
                new butvan.agent.network.dailycontext.dto.DailyContextDtos.SummaryResponse(
                        DAY,
                        new butvan.agent.network.dailycontext.dto.DailyContextDtos.WeatherResponse(
                                "杭州", "多云", "101", 22.0, "°C", 22.0, 60, "东风", 2.0, "m/s", ""),
                        null, null, null));
        when(expenseAnalytics.reportExpenses(eq("owner"), eq(DAY.minusDays(28)), eq(DAY))).thenReturn(List.of(
                new butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry(
                        "e1", DAY.minusDays(5), LocalTime.of(12, 0), "餐饮", "", new java.math.BigDecimal("50.00"), "CNY", "finance", NOW),
                new butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry(
                        "e2", DAY.minusDays(4), LocalTime.of(12, 0), "餐饮", "", new java.math.BigDecimal("50.00"), "CNY", "finance", NOW),
                new butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry(
                        "e3", DAY.minusDays(2), LocalTime.of(12, 0), "交通", "", new java.math.BigDecimal("50.00"), "CNY", "finance", NOW),
                new butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry(
                        "e4", DAY, LocalTime.of(13, 30), "数码配件", "", new java.math.BigDecimal("360.00"), "CNY", "finance", NOW)
        ));
        outreach.tick("owner");
        outreach.tick("owner");
        verify(channel, times(1)).send(eq("target"), anyString(), contains("【天气恢复提醒】杭州天气已转为多云"));
        verify(channel, times(1)).send(eq("target"), anyString(), contains("【支出变化提醒】今日（2026-10-30）已记录 CNY 支出 360.00（主要分类：数码配件）"));

        // 6. 晚上 21:00 (13:00Z) 生成并呈现今日复盘：早上与下午的信号提醒不阻塞晚间每日复盘发送
        TIME.set(NOW);
        reviews.prepare("owner", new Prepare(DAY, 0, false));
        reviews.tick();
        outreach.tick("owner");
        assertEquals("PRESENTED", outreach.candidates("owner").getFirst().state());
        assertEquals(3, outreach.nudges("owner").size());

        // 7. 重置后清除信号基线与短消息
        reset.clean("owner");
        assertTrue(repository.signalState("owner", "WEATHER").isEmpty());
        assertTrue(repository.signalState("owner", "EXPENSE").isEmpty());
    }
    private static Path temporary() {
        try { return Files.createTempDirectory("life-review-test-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
