package butvan.agent.agents.session;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
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
        assertTrue(new ApproximateTokenCounter().count(actualText) <= 1_400);
    }

    static class FrameworkHistory {
        @Tool(name = "session_history", description = "框架旧实现")
        public String history() { return "unbounded"; }
    }
}
