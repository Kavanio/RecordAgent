package butvan.agent.network.review;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.review.*;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.review.controller.ReviewController;
import butvan.agent.network.review.dto.ReviewCoachDtos.*;
import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.dto.ReviewMemoryDtos.*;
import butvan.agent.network.review.model.ReviewModels.ModelIdentity;
import butvan.agent.network.review.repository.*;
import butvan.agent.network.review.service.*;
import butvan.agent.network.study.model.StudyModels.StudyStatistics;
import butvan.agent.network.study.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用 fake 生成器验证预览、预算、反馈与邮件冻结；不调用真实模型或用户数据。 */
@SpringBootTest(classes = ReviewCoachIntegrationTest.Application.class)
class ReviewCoachIntegrationTest {
    private static final Path DATABASE = temporary();
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final AtomicLong TIME = new AtomicLong(NOW.toEpochMilli());
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({LocalDatabaseConfiguration.class, ReviewRepository.class, ReviewCoachRepository.class,
            ReviewMemoryRepository.class, ReviewAuthorizationService.class, ReviewFactCollector.class,
            ReviewFactService.class, ReviewGoalService.class, ReviewDataService.class, ReviewResetParticipant.class,
            ReviewCoachService.class, ReviewMemoryService.class, ReviewDeliveryService.class,
            ReviewMemoryReaderAdapter.class,
            ReviewController.class, ApiExceptionHandler.class})
    static class Application {
        @Bean Clock automationClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return Instant.ofEpochMilli(TIME.get()); }
            };
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE::toString);
    }
    @MockitoBean ReviewModelCatalog models;
    @MockitoBean ReviewGenerator generator;
    @MockitoBean FinanceService finance;
    @MockitoBean ExpenseAnalyticsService expenses;
    @MockitoBean DailyEventService daily;
    @MockitoBean StudyService study;
    @MockitoBean CurrentUserProvider user;
    @jakarta.annotation.Resource ReviewAuthorizationService authorization;
    @jakarta.annotation.Resource ReviewCoachService coach;
    @jakarta.annotation.Resource ReviewMemoryService memory;
    @jakarta.annotation.Resource ReviewMemoryReader memoryReader;
    @jakarta.annotation.Resource ReviewDeliveryService deliveries;
    @jakarta.annotation.Resource JdbcTemplate jdbc;
    @jakarta.annotation.Resource ReviewResetParticipant reset;
    @jakarta.annotation.Resource ReviewGoalService goals;
    private String owner;

    @BeforeEach void setup() {
        TIME.set(NOW.toEpochMilli());
        owner = "coach-" + UUID.randomUUID();
        when(user.currentUserId()).thenReturn(owner);
        when(models.resolve(anyString(), anyString())).thenAnswer(call ->
                new ModelIdentity(call.getArgument(0), call.getArgument(1), "openai", "connection-v1"));
        when(daily.getTodoOccurrences(anyString(), any())).thenReturn(List.of());
        when(study.getStatistics(anyString(), any(), any(), any()))
                .thenReturn(new StudyStatistics(DAY, DAY, 3600, 3600, 1, 1, List.of()));
        when(generator.generate(any())).thenAnswer(call -> {
            var request = (ReviewGenerationRequest) call.getArgument(0);
            String evidence = request.evidence().getFirst().id();
            return new ReviewDraft(1, "仅覆盖已结束自然日", List.of(new ReviewDraft.Observation(
                    "study.start", "FACT_OBSERVATION", "本期学习 " + request.evidence().getFirst().value() + " 秒",
                    List.of(evidence), List.of(), "还无法判断持续性")), List.of(),
                    List.of(new ReviewDraft.Action("study.start", null, "small-start-v1", "先做五分钟",
                            "降低启动成本", List.of(evidence), 7, "是否完成一次")));
        });
    }

    @Test void previewRequiresPaidCallConfirmationAndProducesValidatedDraft() {
        permit(owner, Set.of("STUDY"));
        assertThrows(IllegalArgumentException.class,
                () -> coach.preview(owner, new PreviewRequest("req-1", "DAILY_REPORT", DAY, DAY, "UTC", false)));
        var view = coach.preview(owner, new PreviewRequest("req-2", "DAILY_REPORT", DAY, DAY, "UTC", true));
        assertEquals("SUCCEEDED", view.state());
        assertEquals(1, view.actions().size());
        assertEquals(1, view.observations().size());
        assertTrue(view.coverageNote().contains("已结束自然日"));
        verify(generator, times(1)).generate(any());
        assertTrue(memory.list(owner).actions().isEmpty(), "预览不创建长期建议");
        assertTrue(memory.list(owner).observations().isEmpty(), "预览不创建长期观察");
    }

    @Test void revokedDuringGenerationCannotPublishMemory() {
        var grant = permit(owner, Set.of("STUDY"));
        doAnswer(call -> {
            authorization.revoke(owner, grant.id(), new RevokeRequest(grant.revision()));
            var request = (ReviewGenerationRequest) call.getArgument(0);
            return draft(request);
        }).when(generator).generate(any());
        String id = generateAutomatic();
        assertNotEquals("SUCCEEDED", coach.get(owner, id).state());
        assertTrue(memory.list(owner).actions().isEmpty());
        assertTrue(memory.list(owner).observations().isEmpty());
    }

    @Test void repeatedPreviewReusesSameJobAndBudgetBlocksSecondScheduledReview() {
        permit(owner, Set.of("STUDY"), 1);
        var first = coach.preview(owner, new PreviewRequest("req-a", "DAILY_REPORT", DAY, DAY, "UTC", true));
        var second = coach.preview(owner, new PreviewRequest("req-b", "DAILY_REPORT", DAY, DAY, "UTC", true));
        assertEquals(first.id(), second.id());
        verify(generator, times(1)).generate(any());
        var scheduled = coach.schedule(owner, "DAILY_REPORT", DAY, DAY, "UTC");
        assertTrue(scheduled.isPresent());
        coach.tick();
        // 预览不占自动额度；当日上限为 1，因此首个自动任务执行后额度用尽。
        var next = coach.schedule(owner, "DAILY_REPORT", DAY.minusDays(1), DAY.minusDays(1), "UTC").orElseThrow();
        coach.tick();
        assertEquals("SKIPPED", coach.get(owner, next).state());
    }

    @Test void feedbackIsIdempotentAndCannotReportResultBeforeAdoption() {
        permit(owner, Set.of("STUDY"));
        var view = coach.get(owner, generateAutomatic());
        var action = memory.list(owner).actions().getFirst();
        assertThrows(IllegalArgumentException.class, () -> memory.feedback(owner, action.id(),
                new FeedbackRequest("req-result", action.revision(), "COMPLETED", null, null, DAY, "")));
        var accepted = memory.feedback(owner, action.id(), new FeedbackRequest("req-accept", action.revision(),
                "ACCEPTED", "先做三分钟", "完成一次即算", DAY.plusDays(7), "先试试"));
        assertEquals("ACCEPTED", accepted.state());
        assertEquals("先做三分钟", accepted.title());
        var replay = memory.feedback(owner, action.id(), new FeedbackRequest("req-accept", action.revision(),
                "ACCEPTED", "先做三分钟", "完成一次即算", DAY.plusDays(7), "先试试"));
        assertEquals(accepted.revision(), replay.revision());
        assertEquals("ACCEPTED", replay.state());
        var done = memory.feedback(owner, action.id(), new FeedbackRequest("req-done", accepted.revision(),
                "COMPLETED", null, null, DAY, "做到了"));
        assertEquals("COMPLETED", done.state());
        assertTrue(view.actions().size() == 1);
    }

    @Test void observationsCanBeRejectedAndExpire() {
        permit(owner, Set.of("STUDY"));
        generateAutomatic();
        var observation = memory.list(owner).observations().getFirst();
        assertThrows(IllegalArgumentException.class, () -> memory.correct(owner, observation.id(),
                new CorrectionRequest("req-correction", observation.revision(), "CORRECTED", " ")));
        var rejected = memory.correct(owner, observation.id(), new CorrectionRequest(
                "req-reject", observation.revision(), "REJECTED", "不符合我的情况"));
        assertEquals("REJECTED", rejected.state());
        assertTrue(memory.list(owner).observations().stream().anyMatch(item -> item.id().equals(observation.id()) && "REJECTED".equals(item.state())));
    }

    @Test void authoritativeMemoryHonorsCorrectionOwnerExpiryAndRevocation() {
        var grant = permit(owner, Set.of("STUDY"));
        generateAutomatic();
        var initial = memoryReader.readForAuthorizedReview(owner);
        assertEquals(1, initial.actions().size());
        assertEquals(1, initial.observations().size());
        var observation = memory.list(owner).observations().getFirst();
        memory.correct(owner, observation.id(), new CorrectionRequest("correct-shared", observation.revision(),
                "CORRECTED", "这次情况不能代表长期习惯"));
        assertTrue(memoryReader.readForAuthorizedReview(owner).observations().isEmpty());
        String other = "coach-" + UUID.randomUUID();
        permit(other, Set.of("STUDY"));
        assertTrue(memoryReader.readForAuthorizedReview(other).actions().isEmpty());
        authorization.revoke(owner, grant.id(), new RevokeRequest(grant.revision()));
        assertThrows(IllegalArgumentException.class, () -> memoryReader.readForAuthorizedReview(owner));
    }

    @Test void waitingDeliveryFreezesBaseReportAfterDeadlineAndRevokeDisablesIt() {
        permit(owner, Set.of("STUDY"));
        taskRow();
        runRow("run-1", "基础报告", "<html><body>基础</body></html>", 1);
        String reviewId = coach.schedule(owner, "DAILY_REPORT", DAY, DAY, "UTC").orElseThrow();
        deliveries.register(owner, "run-1", reviewId, "基础报告", "<html><body>基础</body></html>", 90);
        deliveries.freezeReady();
        assertEquals("WAITING", jdbc.queryForObject("SELECT email_status FROM automation_run WHERE id='run-1'", String.class));
        TIME.addAndGet(Duration.ofSeconds(91).toMillis());
        deliveries.freezeReady();
        assertEquals("PENDING", jdbc.queryForObject("SELECT email_status FROM automation_run WHERE id='run-1'", String.class));
        assertEquals("基础报告", jdbc.queryForObject("SELECT content FROM automation_run WHERE id='run-1'", String.class));
        assertNull(jdbc.queryForObject("SELECT final_text FROM automation_review_delivery WHERE run_id='run-1'", String.class));
        assertTrue(jdbc.queryForObject("SELECT error FROM automation_run WHERE id='run-1'", String.class).contains("基础报告"));
    }

    @Test void revokeDisablesUnfrozenDeliveryWithoutTouchingFrozenCopy() {
        var grant = permit(owner, Set.of("STUDY"));
        taskRow();
        runRow("run-2", "基础", "<html></html>", 2);
        String reviewId = coach.schedule(owner, "DAILY_REPORT", DAY, DAY, "UTC").orElseThrow();
        deliveries.register(owner, "run-2", reviewId, "基础", "<html></html>", 90);
        authorization.revoke(owner, grant.id(), new RevokeRequest(grant.revision()));
        assertEquals("DISABLED", jdbc.queryForObject("SELECT state FROM automation_review_delivery WHERE run_id='run-2'", String.class));
        deliveries.freezeReady();
        assertEquals("PENDING", jdbc.queryForObject("SELECT email_status FROM automation_run WHERE id='run-2'", String.class));
        assertEquals("基础", jdbc.queryForObject("SELECT content FROM automation_run WHERE id='run-2'", String.class));
    }

    @Test void factoryResetErasesDerivedMemoryButPreservesManualGoalsAndFrozenBusinessReports() {
        permit(owner, Set.of("STUDY"));
        var goal = goals.save(owner, null, new SaveGoal(0, "持续学习", "保护人工目标", "每周一次", "ACTIVE"));
        String reviewId = generateAutomatic();
        var action = memory.list(owner).actions().getFirst();
        memory.feedback(owner, action.id(), new FeedbackRequest("reset-feedback", action.revision(),
                "ACCEPTED", null, null, DAY.plusDays(7), "个人反馈"));
        String other = "other-" + owner;
        goals.save(other, null, new SaveGoal(0, "另一用户目标", "", "", "ACTIVE"));
        taskRow();
        runRow("run-reset-frozen", "基础业务报告", "<html><body>基础业务报告</body></html>", 5);
        deliveries.register(owner, "run-reset-frozen", reviewId, "基础业务报告", "<html><body>基础业务报告</body></html>", 90);
        deliveries.freezeReady();
        String frozen = jdbc.queryForObject("SELECT content FROM automation_run WHERE id='run-reset-frozen'", String.class);
        assertTrue(frozen.contains("AI 长期复盘"));
        reset.clean(owner); reset.clean(owner);
        assertFalse(authorization.settings(owner).enabled());
        assertFalse(authorization.settings(owner).authorized());
        assertEquals(goal, goals.list(owner).getFirst());
        assertEquals(1, goals.list(other).size());
        assertTrue(memory.list(owner).actions().isEmpty());
        assertTrue(memory.list(owner).observations().isEmpty());
        assertTrue(coach.history(owner, 20).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review_snapshot WHERE owner_id=?", Integer.class, owner));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM review_feedback_request WHERE owner_id=?", Integer.class, owner));
        assertEquals(frozen, jdbc.queryForObject("SELECT content FROM automation_run WHERE id='run-reset-frozen'", String.class));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM automation_review_delivery WHERE run_id='run-reset-frozen'", String.class));
        assertEquals("ENABLED", jdbc.queryForObject("SELECT status FROM automation_task WHERE id=?", String.class, taskId()));
    }

    private String generateAutomatic() {
        String id = coach.schedule(owner, "DAILY_REPORT", DAY, DAY, "UTC").orElseThrow();
        coach.tick();
        return id;
    }

    private ReviewDraft draft(ReviewGenerationRequest request) {
        String evidence = request.evidence().getFirst().id();
        return new ReviewDraft(1, "仅覆盖已结束自然日", List.of(new ReviewDraft.Observation(
                "study.start", "FACT_OBSERVATION", "本期存在学习记录", List.of(evidence), List.of(), "还无法判断持续性")),
                List.of(), List.of(new ReviewDraft.Action("study.start", null, "small-start-v1", "先做五分钟",
                "降低启动成本", List.of(evidence), 7, "是否完成一次")));
    }

    private GrantView permit(String owner, Set<String> domains) {
        return permit(owner, domains, 3);
    }

    private GrantView permit(String owner, Set<String> domains, int dailyLimit) {
        int revision = authorization.settings(owner).revision();
        var saved = authorization.save(owner, new SaveSettings(revision, true,
                Set.of("DAILY_REPORT", "WEEKLY_REPORT", "MONTHLY_REPORT"), domains, 180, dailyLimit, "provider-a", "model-a"));
        return authorization.grant(owner, new GrantRequest(saved.revision(), ReviewPolicy.VERSION, true));
    }

    /** 最小自动任务行，仅用于满足邮件冻结副本的外键。 */
    private void taskRow() {
        jdbc.update("INSERT INTO automation_task(id,owner_id,rule_title,rule_content,rule_kind,rule_trigger,rule_timezone,rule_at_time,rule_once_at,rule_weekdays,rule_minutes,rule_break_minutes,rule_window_start,rule_window_end,rule_desktop,rule_email,rule_confirm,rule_sound,rule_expense,rule_todo,rule_study,status,version,next_at,active_seconds,updated_at) VALUES(?,?,'日报','','DAILY_REPORT','DAILY','UTC','08:00',NULL,127,1,1,'00:00','00:00',1,1,0,0,1,1,1,'ENABLED',1,NULL,0,?)",
                taskId(), owner, NOW.toEpochMilli());
    }

    private void runRow(String id, String content, String html, int offset) {
        jdbc.update("INSERT INTO automation_run(id,task_id,owner_id,title,content,planned_at,created_at,source,status,desktop_status,email_status,recipient,confirmation,sound,error,email_html,email_queued_at) VALUES(?,?,?,'日报',?,?,?,'SCHEDULED','SUCCESS','SKIPPED','WAITING','a@example.test','NONE',0,'',?,?)",
                id, taskId(), owner, content, NOW.toEpochMilli() + offset, NOW.toEpochMilli(), html, NOW.toEpochMilli());
    }

    private String taskId() { return "task-" + owner; }

    private static Path temporary() {
        try { return Files.createTempDirectory("review-coach-").resolve("test.db"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
}
