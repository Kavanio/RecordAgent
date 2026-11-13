package butvan.agent.network.review.life;

import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.review.PersonalDeliveryPort;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionStatus;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.review.life.LifeCompanionModels.*;
import butvan.agent.network.review.model.ReviewModels.Fact;
import butvan.agent.network.review.model.ReviewModels.ModelIdentity;
import butvan.agent.network.review.model.ReviewModels.SourceStatus;
import butvan.agent.network.review.service.ReviewFactCollector;
import butvan.agent.network.review.service.ReviewGoalService;
import butvan.agent.network.review.service.ReviewModelCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = LifeCompanionServiceTest.App.class)
class LifeCompanionServiceTest {
    private static final Path ROOT = tempDir();
    private static final Instant START = Instant.parse("2026-11-08T11:00:00Z"); // 19:00 Asia/Shanghai
    private static final AtomicReference<Instant> TIME = new AtomicReference<>(START);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            LocalDatabaseConfiguration.class,
            LifeReviewRepository.class,
            LifeMailCredentials.class,
            LifeAuthorizationService.class,
            LifeDeliveryService.class,
            LifeCompanionStore.class,
            LifeCompanionCollector.class,
            LifeCompanionSynthesisService.class,
            LifeCompanionStreamService.class,
            LifeCompanionService.class
    })
    static class App {
        @Bean
        AgentStorageProperties storageProperties() {
            return new AgentStorageProperties(ROOT.resolve("agent"));
        }

        @Bean
        ResetBarrier barrier(AgentStorageProperties props) {
            return new ResetBarrier(props);
        }

        @Bean
        PersonalContextService personalContextService(AgentStorageProperties props) {
            return new PersonalContextService(props, new ApproximateTokenCounter(), new ObjectMapper());
        }

        @Bean
        Clock automationClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return TIME.get(); }
            };
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("butvan.database.path", () -> ROOT.resolve("companion-test.db").toString());
    }

    @MockitoBean ReviewModelCatalog modelCatalog;
    @MockitoBean PersonalDeliveryPort feishuChannel;
    @MockitoBean SessionCatalogService sessions;
    @MockitoBean TranscriptService transcripts;
    @MockitoBean RecordService records;
    @MockitoBean ReviewGoalService goals;
    @MockitoBean LifeActivityService activity;
    @MockitoBean ReviewFactCollector factCollector;

    @jakarta.annotation.Resource LifeCompanionService companion;
    @jakarta.annotation.Resource LifeCompanionStore store;
    @jakarta.annotation.Resource LifeReviewRepository repository;
    @jakarta.annotation.Resource PersonalContextService personalContext;

    @BeforeEach
    void setUp() {
        TIME.set(START);
        repository.reset("owner");
        store.reset("owner");
        when(modelCatalog.resolveFirstEnabled())
                .thenReturn(new ModelIdentity("provider-1", "qwen-max", "openai", "fp-1"));
        when(modelCatalog.resolve("provider-1", "qwen-max"))
                .thenReturn(new ModelIdentity("provider-1", "qwen-max", "openai", "fp-1"));
        when(feishuChannel.target()).thenReturn(new PersonalDeliveryPort.Target(true, "feishu-user", "本人飞书"));
        when(feishuChannel.send(anyString(), anyString(), anyString()))
                .thenReturn(new PersonalDeliveryPort.Attempt("SENT", "msg-1"));

        SessionSummaryDto desktopSession = new SessionSummaryDto(
                "desktop-main-1", SessionKind.GENERAL, null, "日常陪伴", "",
                START, START, SessionStatus.ACTIVE);
        SessionSummaryDto feishuSession = new SessionSummaryDto(
                "feishu-main-1", SessionKind.GENERAL, null, "飞书对话", "",
                START, START, SessionStatus.ACTIVE);
        when(sessions.resolveOrCreateCompanionSession(false)).thenReturn(desktopSession);
        when(sessions.resolveOrCreateCompanionSession(true)).thenReturn(feishuSession);
        when(sessions.listActive()).thenReturn(List.of(desktopSession, feishuSession));
        when(transcripts.recent(eq("desktop-main-1"), anyInt())).thenReturn(List.of(
                new TranscriptMessageDto("m-1", "turn-1", TranscriptMessageDto.MessageRole.USER,
                        "这周正在把主动陪伴与认知模块重构完", START.minusSeconds(3600),
                        TranscriptMessageDto.MessageStatus.COMPLETED, null, List.of(), null, null)
        ));
        when(records.journalFragments(eq("owner"), any(), any())).thenReturn(List.of(
                new butvan.agent.network.record.model.RecordModels.JournalFragment(
                        "j-1", LocalDate.of(2026, 11, 8),
                        "下午跑了五公里，脑子清醒了不少", START.minusSeconds(7200), false)
        ));
        when(goals.list("owner")).thenReturn(List.of());
        when(activity.lastInteractionAt(eq("owner"), any(), any())).thenReturn(Optional.of(START.minusSeconds(3600)));
        when(factCollector.collect(eq("owner"), any(), any(), any(), any())).thenReturn(new ReviewFactCollector.Collected(
                List.of(
                        new Fact("TODO", "completed_count", "all", "", "项", 3),
                        new Fact("STUDY", "focus_minutes", "all", "", "分钟", 90),
                        new Fact("FINANCE", "expense_total", "all", "CNY", "元", 128)
                ),
                List.of(new SourceStatus("TODO", "AVAILABLE"), new SourceStatus("STUDY", "AVAILABLE"), new SourceStatus("FINANCE", "AVAILABLE"))
        ));
    }

    @Test
    void enableCompanionLearnsCognitionAndAdaptsToBusyHoursBeforeFollowingUp() {
        CompanionView enabled = companion.enableCompanion("owner", new EnableCompanionRequest(
                true, true, true, true, false, "", "", false, "", 993, "", "", "", "", "", "", "Asia/Shanghai"
        ));
        assertTrue(enabled.companionEnabled());
        assertEquals("desktop-main-1", enabled.primarySessionId());
        assertTrue(enabled.cognition().activeFocusAndBlockers().contains("跑了五公里"));
        assertTrue(enabled.cognition().activeFocusAndBlockers().contains("completed_count=3"));
        assertTrue(enabled.cognition().activeFocusAndBlockers().contains("focus_minutes=90"));
        assertTrue(enabled.cognition().activeFocusAndBlockers().contains("expense_total=128"));
        assertTrue(enabled.cognition().pendingHooksAndCuriosities().contains("主动陪伴与认知模块"));
        // 严守画像修订边界：后台学习不得静默覆盖 PROFILE.md，仅通过受控上下文端口按需注入
        assertTrue(personalContext.get("owner").content().isBlank());
        var fragment = companion.recallCognition("owner", "最近有什么进展？");
        assertTrue(fragment.isPresent());
        assertTrue(fragment.get().content().contains("主动陪伴与认知模块"));
        assertTrue(fragment.get().stillCurrent().getAsBoolean());

        // 手动触发一次主动问候：应写入桌面主对话与飞书隔离对话 transcript、生成桌面通知并发送飞书
        CompanionView afterReach = companion.reachOutNow("owner", null);
        assertEquals(1, afterReach.rhythm().unansweredCount());
        assertEquals(1, afterReach.pendingDesktopNotices().size());
        verify(transcripts, atLeastOnce()).appendProactiveAssistantMessage(eq("desktop-main-1"), anyString());
        verify(transcripts, atLeastOnce()).appendProactiveAssistantMessage(eq("feishu-main-1"), anyString());
        verify(feishuChannel, atLeastOnce()).send(eq("feishu-user"), anyString(), anyString());

        // 确认桌面通知后待弹列表清空
        String noticeId = afterReach.pendingDesktopNotices().getFirst().id();
        companion.acknowledgeDesktopNotice("owner", noticeId);
        assertTrue(companion.view("owner").pendingDesktopNotices().isEmpty());

        // 模拟次日白天 14:00（处于 09:00-18:00 忙碌时段），且用户尚未回复（unansweredCount = 1）
        Instant busyTime = Instant.parse("2026-11-09T06:00:00Z"); // 14:00 Asia/Shanghai
        CompanionState current = store.load("owner");
        store.save("owner", new CompanionState(current.cognition(), new RhythmState(
                true, 1, false, current.rhythm().lastOutreachHook(), current.rhythm().lastLearnedAt(),
                current.rhythm().lastDataFingerprint(), START, START.minusSeconds(3600), busyTime.minusSeconds(60)
        ), current.desktopNotices()));

        TIME.set(busyTime);
        clearInvocations(transcripts, feishuChannel);
        companion.tick("owner");

        // 忙时应当自动顺延至 18:15 之后，不打扰用户
        CompanionView deferredView = companion.view("owner");
        assertTrue(deferredView.rhythm().busyDeferred());
        assertEquals(1, deferredView.rhythm().unansweredCount());
        verify(transcripts, never()).appendProactiveAssistantMessage(anyString(), anyString());

        // 推进时间到 18:20（已过忙碌时段），tick 应当自然跟进询问
        Instant afterBusyTime = Instant.parse("2026-11-09T10:20:00Z"); // 18:20 Asia/Shanghai
        TIME.set(afterBusyTime);
        companion.tick("owner");

        CompanionView followedUp = companion.view("owner");
        assertFalse(followedUp.rhythm().busyDeferred());
        assertEquals(2, followedUp.rhythm().unansweredCount());
        verify(transcripts, times(1)).appendProactiveAssistantMessage(eq("desktop-main-1"),
                argThat(msg -> msg.contains("忙") || msg.contains("进展")));

        // 同一天达到自动触达上限 1 次后不再二次打扰；用户发送消息后立即重置未回复计数与忙时顺延状态
        TIME.set(Instant.parse("2026-11-09T13:30:00Z")); // 21:30 Asia/Shanghai
        companion.tick("owner");
        assertEquals(2, companion.view("owner").rhythm().unansweredCount());

        // 22:30 Asia/Shanghai 进入安静时段（22:00-09:00）同样不触发
        TIME.set(Instant.parse("2026-11-09T14:30:00Z"));
        companion.tick("owner");
        verify(transcripts, times(1)).appendProactiveAssistantMessage(eq("desktop-main-1"),
                argThat(msg -> msg.contains("忙") || msg.contains("进展")));

        // 用户在任意渠道主动发言：立即重置未回复计数
        companion.onUserTurnRecorded("owner", "desktop-main-1",
                butvan.agent.agents.memory.MemorySourceRegistry.Channel.DESKTOP);
        CompanionView afterReply = companion.view("owner");
        assertEquals(0, afterReply.rhythm().unansweredCount());
        assertFalse(afterReply.rhythm().busyDeferred());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("companion-test-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
