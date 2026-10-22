package butvan.agent.network.automation;

import butvan.agent.network.automation.dto.AutomationDtos.*;
import butvan.agent.network.automation.dto.TaskMailDtos;
import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.automation.repository.AutomationRepository;
import butvan.agent.network.automation.service.*;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.event.ApplicationEvents;
import butvan.agent.network.automation.event.TasksChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用临时 SQLite、可控时间及模拟投递验证任务状态闭环，不接触用户配置或 SMTP。 */
@RecordApplicationEvents
@SpringBootTest(classes={LocalDatabaseConfiguration.class, AutomationRepository.class,
        AutomationService.class, AutomationServiceIntegrationTest.TimeConfig.class})
class AutomationServiceIntegrationTest {
    private static final Path DB = temp();
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { r.add("butvan.database.path", DB::toString); }
    private static Path temp() { try { return Files.createTempDirectory("automation-test-").resolve("butvan.db"); }
        catch (IOException e) { throw new IllegalStateException(e); } }
    @TestConfiguration static class TimeConfig {
        @Bean Clock automationClock() { return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(TIME.get()); }
        }; }
    }
    private static final AtomicLong TIME = new AtomicLong(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
    @Autowired AutomationService service;
    @Autowired AutomationRepository repository;
    @Autowired ApplicationEvents applicationEvents;
    @MockitoBean TaskReportBuilder reports;
    @MockitoBean TaskMailService mail;

    private Spec reminder(String trigger, boolean confirm) {
        return new Spec("喝水提醒", "起来喝水", "REMINDER", trigger, "Asia/Shanghai", "18:01", "2026-09-23T10:01:00Z",
                127, 1, 5, "00:00", "00:00", true, false, confirm, true, false, false, false);
    }
    @Test void snapshotsCarryStableSourceAndIncreasingReadRevision() {
        var first = service.snapshot("owner-snapshot-order");
        var second = service.snapshot("owner-snapshot-order");
        assertNotNull(first.sourceId());
        assertEquals(first.sourceId(), second.sourceId());
        assertTrue(second.revision() > first.revision());
        assertFalse(second.activityOnly());
    }
    @Test void weeklyAndMonthlyReportsPersistTheirScheduleAndTemplate() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        var weekly = new Spec("周报", "", "WEEKLY_REPORT", "WEEKLY", "Asia/Shanghai", "08:30", null,
                1, 45, 5, "00:00", "00:00", true, false, false, false, true, true, true);
        var monthly = new Spec("月报", "", "MONTHLY_REPORT", "MONTHLY", "Asia/Shanghai", "08:30", null,
                127, 45, 5, "00:00", "00:00", true, false, false, false, true, false, true);
        var weekTask = service.save("owner-periods", null, new SaveRequest(weekly, true, 0));
        var monthTask = service.save("owner-periods", null, new SaveRequest(monthly, true, 0));
        var saved = service.snapshot("owner-periods").tasks();
        assertEquals(2, saved.size());
        assertEquals("DIGEST", saved.stream().filter(task -> task.id().equals(weekTask.id())).findFirst().orElseThrow().spec().mailTemplate().style());
        assertEquals("WEEKLY", saved.stream().filter(task -> task.id().equals(weekTask.id())).findFirst().orElseThrow().spec().trigger());
        assertEquals("MONTHLY", saved.stream().filter(task -> task.id().equals(monthTask.id())).findFirst().orElseThrow().spec().trigger());
        assertNotNull(monthTask.nextAt());
    }
    @Test void scheduledRunIsUniqueAndConfirmationIsIdempotent() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        when(reports.build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class)))
                .thenAnswer(call -> new TaskReportBuilder.Report(call.getArgument(1, TaskSpec.class).content(), false));
        var task = service.save("owner-a", null, new SaveRequest(reminder("DAILY", true), true, 0));
        assertEquals(1, service.snapshot("owner-a").tasks().size());
        assertTrue(service.snapshot("owner-b").tasks().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.state("owner-a",task.id(),new StateRequest(false,0)));
        TIME.addAndGet(61_000);
        service.tick(); service.tick();
        var runs = service.history("owner-a", task.id());
        assertEquals(1, runs.size());
        assertEquals("WAITING", runs.getFirst().confirmation());
        assertTrue(service.claimDesktop("owner-a", runs.getFirst().id()));
        assertFalse(service.claimDesktop("owner-a", runs.getFirst().id()));
        service.desktopReceipt("owner-a",runs.getFirst().id(),"SUBMITTED");
        service.confirm("owner-a",runs.getFirst().id()); service.confirm("owner-a",runs.getFirst().id());
        assertEquals("CONFIRMED",service.history("owner-a",task.id()).getFirst().confirmation());
        assertThrows(IllegalArgumentException.class,() -> service.confirm("owner-b",runs.getFirst().id()));
        service.state("owner-a",task.id(),new StateRequest(false,task.version()));
        assertEquals("PAUSED",service.snapshot("owner-a").tasks().getFirst().status());
    }
    @Test void retryReusesStoredReportAndUnknownRequiresExplicitChoice() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        when(mail.settings()).thenReturn(new TaskMailDtos.Settings("smtp.example.test", 587, "sender", "sender@example.test",
                true, true, true, true, "t***@example.test", true));
        when(mail.recipient()).thenReturn("test@example.test");
        when(reports.build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new TaskReportBuilder.Report("已保存的日报内容", false));
        var base = reminder("DAILY",false);
        var mailed = new Spec("日报", "", "DAILY_REPORT", "DAILY", base.timezone(),base.atTime(), null,
                127,45,5,"00:00","00:00",false,true,false,false,true,true,false);
        var task = service.save("owner-mail", null, new SaveRequest(mailed,true,0));
        var run = service.runNow("owner-mail",task.id());
        assertEquals("已保存的日报内容",run.content());
        assertEquals("PENDING",run.emailStatus());
        var claimed = service.claimMail();
        assertNotNull(claimed);
        service.mailResult(claimed,new TaskMailService.Outcome("FAILED","SMTP 配置有误"));
        service.retryMail("owner-mail",run.id(),false);
        claimed = service.claimMail();
        assertEquals(run.id(),claimed.id());
        service.mailResult(claimed,new TaskMailService.Outcome("UNKNOWN","结果未知"));
        assertThrows(IllegalArgumentException.class, () -> service.retryMail("owner-mail",run.id(),false));
        service.retryMail("owner-mail",run.id(),true);
        assertEquals("PENDING",service.history("owner-mail",task.id()).getFirst().emailStatus());
        verify(reports,times(1)).build(anyString(),any(TaskSpec.class),any(Instant.class),any(Instant.class));
    }
    @Test void previewDoesNotCreateOrSend() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        when(reports.build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new TaskReportBuilder.Report("起来喝水",false));
        var preview = service.preview("owner-p",reminder("ONCE",false));
        assertEquals("起来喝水",preview.content());
        assertTrue(service.snapshot("owner-p").tasks().isEmpty());
        verifyNoInteractions(mail);
    }
    @Test void activityNeedsContinuousSamplesAndDoesNotDuplicatePendingReminder() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        when(reports.build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new TaskReportBuilder.Report("起身休息",false));
        var base = reminder("DAILY",true);
        var sedentary = new Spec("久坐提醒","起身休息","SEDENTARY","ACTIVITY",base.timezone(),base.atTime(),null,127,1,5,"00:00","00:00",true,false,true,true,false,false,false);
        var task=service.save("owner-s",null,new SaveRequest(sedentary,true,0));
        service.activity("owner-s",new ActivitySample(0,false,true));
        for(int i=0;i<12;i++){TIME.addAndGet(5_000);service.activity("owner-s",new ActivitySample(0,false,true));}
        assertEquals(1,service.history("owner-s",task.id()).size());
        assertEquals("WAITING",service.history("owner-s",task.id()).getFirst().confirmation());
        TIME.addAndGet(5_000);service.activity("owner-s",new ActivitySample(0,false,true));
        assertEquals(1,service.history("owner-s",task.id()).size());
        service.confirm("owner-s",service.history("owner-s",task.id()).getFirst().id());
        assertEquals(0,service.snapshot("owner-s").tasks().getFirst().activeSeconds());
    }
    @Test void activityProgressDoesNotInvalidateHistoryUnlessItGeneratesARun() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        when(reports.build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new TaskReportBuilder.Report("起身休息", false));
        var spec = new Spec("计时同步", "起身休息", "SEDENTARY", "ACTIVITY", "Asia/Shanghai", "18:01", null,
                127, 1, 5, "00:00", "00:00", true, false, true, true, false, false, false);
        var task = service.save("owner-sync-progress", null, new SaveRequest(spec, true, 0));
        applicationEvents.clear();
        service.activity("owner-sync-progress", new ActivitySample(0, false, true));
        TIME.addAndGet(5_000);
        service.activity("owner-sync-progress", new ActivitySample(0, false, true));
        var mapper = new ObjectMapper();
        assertTrue(applicationEvents.stream(TasksChanged.class).allMatch(event ->
                mapper.valueToTree(event).path("activityOnly").asBoolean()));
        applicationEvents.clear();
        for (int i = 0; i < 11; i++) { TIME.addAndGet(5_000); service.activity("owner-sync-progress", new ActivitySample(0, false, true)); }
        assertEquals(1, service.history("owner-sync-progress", task.id()).size());
        var last = applicationEvents.stream(TasksChanged.class).reduce((previous, next) -> next).orElseThrow();
        assertFalse(mapper.valueToTree(last).path("activityOnly").asBoolean(), "产生执行记录必须通知历史刷新");
    }
    @Test void lockedScreenResetsContinuousUse() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        var base = reminder("DAILY",true);
        var sedentary = new Spec("久坐提醒","起身休息","SEDENTARY","ACTIVITY",base.timezone(),base.atTime(),null,
                127,1,5,"00:00","00:00",true,false,true,true,false,false,false);
        var task = service.save("owner-lock",null,new SaveRequest(sedentary,true,0));
        service.activity("owner-lock",new ActivitySample(0,false,true));
        TIME.addAndGet(5_000); service.activity("owner-lock",new ActivitySample(0,false,true));
        assertEquals(5,service.snapshot("owner-lock").tasks().getFirst().activeSeconds());
        TIME.addAndGet(5_000); service.activity("owner-lock",new ActivitySample(0,true,true));
        assertEquals(5,service.snapshot("owner-lock").tasks().getFirst().activeSeconds());
        assertTrue(service.history("owner-lock",task.id()).isEmpty());
    }
    @Test void explicitRetryAfterOneDayUsesNewQueueTimeAndFrozenHtml() {
        TIME.set(Instant.parse("2026-09-23T10:00:00Z").toEpochMilli());
        when(mail.settings()).thenReturn(new TaskMailDtos.Settings("smtp.example.test", 587, "sender", "sender@example.test",
                true, true, true, true, "t***@example.test", true));
        when(mail.recipient()).thenReturn("test@example.test");
        when(reports.build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new TaskReportBuilder.Report("原报告", false));
        var base = reminder("DAILY", false);
        var template = new MailTemplateView("CUSTOM", "#123456", "原邮件标题", "你好", "落款", "{{content}}");
        var spec = new Spec("日报", "", "DAILY_REPORT", "DAILY", base.timezone(), base.atTime(), null,
                127, 45, 5, "00:00", "00:00", false, true, false, false, true, true, false, template);
        var task = service.save("owner-retry-day", null, new SaveRequest(spec, true, 0));
        assertEquals("DIGEST", service.snapshot("owner-retry-day").tasks().getFirst().spec().mailTemplate().style());
        var preview = service.preview("owner-retry-day", spec);
        assertTrue(preview.html().contains("A day, recorded."));
        assertTrue(service.history("owner-retry-day", task.id()).isEmpty());
        // 其他用例可能留下已明确重试的队列，先完成它们，避免共享时钟影响本例。
        butvan.agent.network.automation.model.TaskRun pending;
        while ((pending = service.claimMail()) != null) service.mailResult(pending, new TaskMailService.Outcome("SUBMITTED", ""));
        var run = service.runNow("owner-retry-day", task.id());
        var first = service.claimMail();
        assertEquals(run.id(), first.id());
        service.mailResult(first, new TaskMailService.Outcome("FAILED", "测试失败"));
        var changed = new Spec(spec.title(), spec.content(), spec.kind(), spec.trigger(), spec.timezone(), spec.atTime(), null,
                127, 45, 5, "00:00", "00:00", false, true, false, false, true, true, false,
                new MailTemplateView("CUSTOM", "#654321", "新邮件标题", "", "", "全新正文"));
        service.save("owner-retry-day", task.id(), new SaveRequest(changed, true, task.version()));
        TIME.addAndGet(2 * 86_400_000L);
        service.retryMail("owner-retry-day", run.id(), false);
        var retry = service.claimMail();
        assertNotNull(retry);
        assertEquals(run.id(), retry.id());
        assertEquals(first.content(), retry.content());
        assertEquals(first.emailHtml(), retry.emailHtml());
        assertTrue(retry.emailHtml().contains("A day, recorded."));
        assertFalse(retry.emailHtml().contains("新邮件标题"));
        assertEquals(TIME.get(), retry.emailQueuedAt());
        service.mailResult(retry, new TaskMailService.Outcome("SUBMITTED", ""));
        assertEquals("SUBMITTED", service.history("owner-retry-day", task.id()).getFirst().emailStatus());
        verify(reports, times(2)).build(anyString(), any(TaskSpec.class), any(Instant.class), any(Instant.class));
    }

}
