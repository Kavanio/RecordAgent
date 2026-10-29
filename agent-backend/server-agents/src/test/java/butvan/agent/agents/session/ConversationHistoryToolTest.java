package butvan.agent.agents.session;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import butvan.agent.agents.tool.ToolRegistry;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖历史工具的时间归因、用户／助手区分、范围校验与框架覆盖顺序。 */
class ConversationHistoryToolTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recentUsesUserOriginalsAndRecordedTimeWithoutThinkingOrModelSummaries() throws Exception {
        var storage = new AgentStorageProperties(root);
        var catalog = new SessionCatalogService(storage, new CurrentUserProvider(), mapper);
        var transcripts = new TranscriptService(storage, mapper);
        String previous = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "之前", null)).id();
        Files.writeString(storage.transcriptFile(previous),
                "{\"id\":\"u\",\"turnId\":\"t\",\"role\":\"USER\",\"content\":\"老师说跟不上时代\",\"createdAt\":\"2026-10-29T02:33:00Z\",\"status\":\"COMPLETED\"}\n"
                        + "{\"id\":\"a\",\"turnId\":\"t\",\"role\":\"ASSISTANT\",\"content\":\"你很焦虑\",\"thinking\":\"hidden\",\"createdAt\":\"2026-10-29T02:33:01Z\",\"status\":\"COMPLETED\"}\n");
        String current = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "现在", null)).id();
        var context = RuntimeContext.builder().sessionId(current).userId("local-default").build();
        var tool = new ConversationHistoryTool(catalog, transcripts, mapper);
        String result = mapper.writeValueAsString(tool.recent(context, 1));
        assertTrue(result.contains("老师说跟不上时代"));
        assertTrue(result.contains("2026-10-29T02:33:00Z"));
        assertFalse(result.contains("你很焦虑"));
        assertFalse(result.contains("hidden"));
        assertTrue(mapper.writeValueAsString(tool.history(context, previous, 12, true)).contains("ASSISTANT"));
        assertThrows(IllegalArgumentException.class, () -> tool.history(context, "unknown", 6, true));
        assertTrue(new ApproximateTokenCounter().count(result) <= 1_400);
    }

    @Test
    void boundedHistoryReportsItsWindowAndNeverClaimsACompleteConversation() throws Exception {
        var storage = new AgentStorageProperties(root);
        var catalog = new SessionCatalogService(storage, new CurrentUserProvider(), mapper);
        var transcripts = new TranscriptService(storage, mapper);
        String previous = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "之前", null)).id();
        for (int i = 0; i < 8; i++) {
            String turn = transcripts.appendUserMessage(previous, "问题" + i);
            transcripts.appendAssistantMessage(previous, turn, "回答" + i, null,
                    TranscriptMessageDto.MessageStatus.COMPLETED, null, List.of(), null);
        }
        String current = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "现在", null)).id();
        var context = RuntimeContext.builder().sessionId(current).userId("local-default").build();
        var tool = new ConversationHistoryTool(catalog, transcripts, mapper);
        var recent = mapper.valueToTree(tool.recent(context, null));

        assertEquals(3, recent.path("records").size(), "尾部6条按用户与助手合计计数，仅返回3条用户消息");
        assertEquals("问题5", recent.path("records").get(0).path("text").asText());
        assertEquals(6, recent.path("coverage").path("tailMessageLimit").asInt());
        assertEquals("最近最多1个其他同范围会话", recent.path("coverage").path("selection").asText());
        assertFalse(recent.path("coverage").path("includesAssistant").asBoolean());
        assertFalse(recent.path("coverage").path("outputTruncated").asBoolean(),
                "未触及输出截断也不代表已经读取完整历史");
        assertTrue(recent.path("note").asText().contains("回复时先说明仅为部分近期记录"));
        assertTrue(recent.path("note").asText().contains("用户与助手合计"));

        var history = mapper.valueToTree(tool.history(context, previous, 2, true));
        assertEquals(2, history.path("coverage").path("tailMessageLimit").asInt());
        assertTrue(history.path("coverage").path("includesAssistant").asBoolean());
        var list = mapper.valueToTree(tool.list(context, 2));
        assertEquals(0, list.path("coverage").path("tailMessageLimit").asInt());
        assertTrue(list.path("coverage").path("selection").asText().contains("标题与更新时间"));
        var empty = mapper.valueToTree(tool.search(context, "不存在的关键词", 1));
        assertTrue(empty.path("records").isEmpty());
        assertEquals(12, empty.path("coverage").path("tailMessageLimit").asInt());
        assertEquals("最近最多20个同范围会话的关键词匹配，最多1项",
                empty.path("coverage").path("selection").asText());
        assertTrue(empty.path("note").asText().contains("空结果不代表所有历史不存在"));
    }

    @Test
    void historyIsBoundedAndApplicationImplementationOverridesFrameworkTool() throws Exception {
        var storage = new AgentStorageProperties(root);
        var catalog = new SessionCatalogService(storage, new CurrentUserProvider(), mapper);
        var transcripts = new TranscriptService(storage, mapper);
        String sid = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "long", null)).id();
        Files.writeString(storage.transcriptFile(sid), "{\"id\":\"large\",\"role\":\"USER\",\"content\":\""
                + "学".repeat(800_000) + "\",\"createdAt\":\"2026-10-29T02:33:00Z\"}\n");
        for (int i = 0; i < 15; i++) transcripts.appendUserMessage(sid, "学".repeat(1_000));
        assertEquals(12, transcripts.recent(sid, 12).size(), "跨UTF-8行尾部读取仍应保留后续完整记录");
        var tool = new ConversationHistoryTool(catalog, transcripts, mapper);
        var ctx = RuntimeContext.builder().sessionId(sid).userId("local-default").build();
        String result = mapper.writeValueAsString(tool.history(ctx, sid, 1000, true));
        assertTrue(new ApproximateTokenCounter().count(result) <= 1_400);
        assertTrue(result.contains("\"truncated\":true"));
        assertTrue(mapper.readTree(result).path("coverage").path("outputTruncated").asBoolean());
        assertTrue(mapper.readTree(result).path("records").size() < 12,
                "范围说明与正文共同占用1400估算Token预算");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FrameworkHistory());
        var registry = new ToolRegistry(List.of(tool), new ToolCapabilityCatalog());
        registry.enableOnDemandSchemas(toolkit);
        assertFalse(toolkit.getTool("session_history").getParameters().toString().contains("agentId"));
        assertNotNull(toolkit.getTool("session_recent"));
        toolkit.updateToolGroups(List.of("memory"), true);
        var response = toolkit.callTool(ToolCallParam.builder().runtimeContext(ctx)
                .toolUseBlock(ToolUseBlock.builder().id("history-test").name("session_history")
                        .content("{\"sessionId\":\"" + sid + "\"}").build())
                .input(Map.of("sessionId", sid)).build()).block(java.time.Duration.ofSeconds(5));
        assertNotNull(response);
        String actualText = ((TextBlock) response.getOutput().getFirst()).getText();
        assertTrue(mapper.readTree(actualText).get("records").isArray(), actualText);
        assertEquals(6, mapper.readTree(actualText).path("coverage").path("tailMessageLimit").asInt());
        assertTrue(mapper.readTree(actualText).path("note").asText().contains("仅为部分近期记录"));
        assertTrue(new ApproximateTokenCounter().count(actualText) <= 1_400);
    }

    @Test
    void emptyRecentResultStillDisclosesScopeAndClampsRequestedLimits() throws Exception {
        var storage = new AgentStorageProperties(root);
        var catalog = new SessionCatalogService(storage, new CurrentUserProvider(), mapper);
        var transcripts = new TranscriptService(storage, mapper);
        String sid = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "唯一会话", null)).id();
        var context = RuntimeContext.builder().sessionId(sid).userId("local-default").build();
        var tool = new ConversationHistoryTool(catalog, transcripts, mapper);
        var recent = tool.recent(context, 999);

        assertTrue(recent.records().isEmpty());
        assertEquals("最近最多3个其他同范围会话", recent.coverage().selection());
        assertTrue(recent.note().contains("仅为部分近期记录"));
        assertFalse(recent.coverage().outputTruncated());
        assertEquals(12, tool.history(context, sid, 999, null).coverage().tailMessageLimit());
        assertEquals("最近最多20个同范围会话的关键词匹配，最多6项",
                tool.search(context, "问题", 999).coverage().selection());
        assertTrue(new ApproximateTokenCounter().count(mapper.writeValueAsString(recent)) <= 1_400);
    }

    static class FrameworkHistory {
        @Tool(name = "session_history", description = "框架旧实现")
        public String history() { return "unbounded"; }
    }
}
