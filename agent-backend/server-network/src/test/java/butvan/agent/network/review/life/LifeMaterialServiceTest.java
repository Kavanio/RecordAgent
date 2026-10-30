package butvan.agent.network.review.life;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.*;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.record.model.RecordModels.JournalFragment;
import butvan.agent.network.review.service.ReviewFactCollector;
import butvan.agent.network.review.life.LifeReviewModels.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实材料组合器的范围与角色测试；领域来源均用替身。 */
class LifeMaterialServiceTest {
    final RecordService records = mock(RecordService.class);
    final SessionCatalogService sessions = mock(SessionCatalogService.class);
    final TranscriptService transcripts = mock(TranscriptService.class);
    final CurrentUserProvider user = new CurrentUserProvider() { @Override public String currentUserId() { return "owner"; } };
    final ReviewFactCollector facts = mock(ReviewFactCollector.class);
    final LifeMaterialService service = new LifeMaterialService(records, sessions, transcripts, user, facts);
    final Instant now = Instant.parse("2026-10-30T13:00:00Z");
    final LocalDate day = LocalDate.of(2026, 10, 30);
    Settings settings(Set<String> sources) {
        return new Settings("owner", 1, true, "g", sources, "p", "m", "f", "Asia/Shanghai", 3, 1, false, false, "", now);
    }
    SessionSummaryDto session(String id, SessionKind kind) {
        return new SessionSummaryDto(id, kind, kind == SessionKind.PROJECT ? "project" : null, "测试", "", now, now, SessionStatus.ACTIVE);
    }
    TranscriptMessageDto message(String id, TranscriptMessageDto.MessageRole role, Instant at, String text) {
        return new TranscriptMessageDto(id, "turn", role, text, at, TranscriptMessageDto.MessageStatus.COMPLETED, null, List.of(), null, null);
    }
    @Test void unselectedSourcesAreNeverRead() {
        var result = service.collect(settings(Set.of()), day, now);
        assertTrue(result.materials().isEmpty()); verifyNoInteractions(records, sessions, transcripts, facts);
    }
    @Test void chatExcludesProjectAssistantFutureAndOutOfWindow() {
        when(sessions.listActive()).thenReturn(List.of(session("general", SessionKind.GENERAL), session("project", SessionKind.PROJECT)));
        when(transcripts.recent("general", 24)).thenReturn(List.of(
                message("u", TranscriptMessageDto.MessageRole.USER, now.minusSeconds(60), "老师表达了时代变化的感受"),
                message("a", TranscriptMessageDto.MessageRole.ASSISTANT, now, "你很焦虑"),
                message("future", TranscriptMessageDto.MessageRole.USER, now.plusSeconds(1), "未来"),
                message("old", TranscriptMessageDto.MessageRole.USER, now.minus(Duration.ofDays(4)), "旧记录")));
        var result = service.collect(settings(Set.of("CHAT")), day, now);
        assertEquals(1, result.materials().size()); assertEquals("chat-u", result.materials().getFirst().id());
        assertTrue(result.coverage().contains("角色筛选前24条")); assertTrue(result.coverage().contains("仅部分近期记录"));
        verify(transcripts, never()).recent(eq("project"), anyInt());
    }
    @Test void chatLimitsFiveSessionsAndTruncatesPlainText() {
        when(sessions.listActive()).thenReturn(java.util.stream.IntStream.range(0, 10).mapToObj(i -> session("s" + i, SessionKind.GENERAL)).toList());
        when(transcripts.recent(anyString(), eq(24))).thenReturn(List.of(message("long", TranscriptMessageDto.MessageRole.USER, now, "文".repeat(2000))));
        var result = service.collect(settings(Set.of("CHAT")), day, now);
        assertEquals(5, result.materials().size()); assertEquals(600, result.materials().getFirst().text().length());
        assertTrue(result.materials().getFirst().truncated()); verify(transcripts, times(5)).recent(anyString(), eq(24));
    }
    @Test void journalsKeepExplicitAttributionAndIgnoreUpdatesAfterCutoff() {
        when(records.journalFragments("owner", day.minusDays(2), day)).thenReturn(List.of(
                new JournalFragment("r1", day.minusDays(1), "昨天尝试翻译", now, true),
                new JournalFragment("r2", day, "迟到内容", now.plusSeconds(1), false)));
        var result = service.collect(settings(Set.of("JOURNAL")), day, now);
        assertEquals(1, result.materials().size()); assertEquals("2026-10-29", result.materials().getFirst().occurredAt());
        verifyNoInteractions(sessions, transcripts, facts);
    }
    @Test void unavailableSourceIsNotRepresentedAsZeroOrNoActivity() {
        when(records.journalFragments(anyString(), any(), any())).thenThrow(new IllegalStateException("private content"));
        var result = service.collect(settings(Set.of("JOURNAL")), day, now);
        assertTrue(result.coverage().contains("读取不可用")); assertFalse(result.coverage().contains("private content"));
    }

    @Test void chatRecallAppendsHistoricalMemoriesWhenAvailable() {
        when(sessions.listActive()).thenReturn(List.of(session("general", SessionKind.GENERAL)));
        when(transcripts.recent("general", 24)).thenReturn(List.of(
                message("u", TranscriptMessageDto.MessageRole.USER, now.minusSeconds(60), "今天继续练英语口语")));
        butvan.agent.agents.memory.MemoryRecallPort recall = request -> new butvan.agent.agents.memory.MemoryRecallPort.Result(
                butvan.agent.agents.memory.MemoryRecallPort.Status.SUCCESS,
                List.of(new butvan.agent.agents.memory.MemoryRecallPort.Fragment(
                        "经历来源 src-1@2026-10-01T10:00:00Z",
                        "历史用户原文：之前用影子跟读练口语效果不错",
                        () -> true)));
        var withRecall = new LifeMaterialService(records, sessions, transcripts, user, facts, recall);
        var result = withRecall.collect(settings(Set.of("CHAT")), day, now);
        assertEquals(2, result.materials().size());
        assertEquals("HISTORICAL_MEMORY", result.materials().get(1).kind());
        assertEquals("src-1", result.materials().get(1).sourceId());
        assertTrue(result.coverage().contains("长期记忆：按已授权经历召回最多三条关联历史原文"));
    }

    @Test void journalOnlySelectionAlsoRecallsHistoricalMemoriesWhenAvailable() {
        when(records.journalFragments("owner", day.minusDays(2), day)).thenReturn(List.of(
                new JournalFragment("r1", day, "今天写手记回顾英语学习", now, false)));
        butvan.agent.agents.memory.MemoryRecallPort recall = request -> new butvan.agent.agents.memory.MemoryRecallPort.Result(
                butvan.agent.agents.memory.MemoryRecallPort.Status.SUCCESS,
                List.of(new butvan.agent.agents.memory.MemoryRecallPort.Fragment(
                        "经历来源 src-journal@2026-10-05T10:00:00Z",
                        "历史用户原文：之前尝试过把每日手记翻成英文",
                        () -> true)));
        var withRecall = new LifeMaterialService(records, sessions, transcripts, user, facts, recall);
        var result = withRecall.collect(settings(Set.of("JOURNAL")), day, now);
        assertEquals(2, result.materials().size());
        assertEquals("HISTORICAL_MEMORY", result.materials().get(1).kind());
        assertEquals("src-journal", result.materials().get(1).sourceId());
        verifyNoInteractions(sessions, transcripts);
    }
}
