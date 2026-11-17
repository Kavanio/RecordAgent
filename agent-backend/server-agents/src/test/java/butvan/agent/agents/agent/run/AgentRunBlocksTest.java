package butvan.agent.agents.agent.run;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.usage.ModelIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunBlocksTest {

    @TempDir
    Path tempDir;

    @Test
    void preservesInterleavedReactSequenceAndFinalSnapshot() {
        AgentRun run = new AgentRun("session-1", "owner", "turn-1", null);

        // 第一轮：思考 -> 说明 -> 连续两次工具调用
        run.record(new AgentStreamEvent.ThinkingDelta("先确定查询关键词。"));
        run.record(new AgentStreamEvent.TextDelta("我先搜一下"));
        run.record(new AgentStreamEvent.TextDelta("今天的动态。"));
        run.record(new AgentStreamEvent.ToolCall("call-1", "web_search", ""));
        run.record(new AgentStreamEvent.ToolCall("call-1", "web_search", "{\"query\":\"AI\"}"));
        run.record(new AgentStreamEvent.ToolCall("call-2", "web_search", "{\"query\":\"LLM\"}"));
        run.record(new AgentStreamEvent.ToolResult("call-1", "web_search", "result-1", AgentStreamEvent.ToolStatus.COMPLETED));
        run.record(new AgentStreamEvent.ToolResult("call-2", "web_search", "result-2", AgentStreamEvent.ToolStatus.COMPLETED));

        // 第二轮：反思 -> 再次工具调用 -> 最终回答（模拟末尾增量被截断，靠 AgentResultEvent 修复）
        run.record(new AgentStreamEvent.ThinkingDelta("再补充查一次细节。"));
        run.record(new AgentStreamEvent.TextDelta("已找到初步线索，再确认一下发布时间。"));
        run.record(new AgentStreamEvent.ToolCall("call-3", "read_file", "{\"path\":\"notes.md\"}"));
        run.record(new AgentStreamEvent.ToolResult("call-3", "read_file", "ok", AgentStreamEvent.ToolStatus.COMPLETED));
        run.record(new AgentStreamEvent.TextDelta("结论：完整"));
        run.recordModelEvent(new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT).textContent("结论：完整答案🙂").build()),
                new ModelIdentity("test", "model"));

        assertEquals("结论：完整答案🙂", run.contentAsString());

        List<TranscriptMessageDto.MessageBlockDto> blocks = run.finalizeBlocks(TranscriptMessageDto.MessageStatus.COMPLETED);
        assertEquals(7, blocks.size());

        assertEquals(TranscriptMessageDto.BlockKind.THINKING, blocks.get(0).kind());
        assertEquals("先确定查询关键词。", blocks.get(0).content());

        assertEquals(TranscriptMessageDto.BlockKind.TEXT, blocks.get(1).kind());
        assertEquals("我先搜一下今天的动态。", blocks.get(1).content());

        assertEquals(TranscriptMessageDto.BlockKind.TOOL_GROUP, blocks.get(2).kind());
        assertEquals(2, blocks.get(2).tools().size());
        assertEquals("call-1", blocks.get(2).tools().get(0).toolCallId());
        assertEquals("{\"query\":\"AI\"}", blocks.get(2).tools().get(0).command());
        assertEquals("result-1", blocks.get(2).tools().get(0).output());

        assertEquals(TranscriptMessageDto.BlockKind.THINKING, blocks.get(3).kind());
        assertEquals("再补充查一次细节。", blocks.get(3).content());

        assertEquals(TranscriptMessageDto.BlockKind.TEXT, blocks.get(4).kind());
        assertEquals("已找到初步线索，再确认一下发布时间。", blocks.get(4).content());

        assertEquals(TranscriptMessageDto.BlockKind.TOOL_GROUP, blocks.get(5).kind());
        assertEquals(1, blocks.get(5).tools().size());
        assertEquals("call-3", blocks.get(5).tools().get(0).toolCallId());

        assertEquals(TranscriptMessageDto.BlockKind.TEXT, blocks.get(6).kind());
        assertEquals("结论：完整答案🙂", blocks.get(6).content());
    }

    @Test
    void finalResultAppendsTextBlockWhenLastRoundTextDeltasWereMissing() {
        AgentRun run = new AgentRun("session-2", "owner", "turn-2", null);
        run.record(new AgentStreamEvent.TextDelta("我先查看文件。"));
        run.record(new AgentStreamEvent.ToolCall("call-1", "read_file", "a.txt"));
        run.record(new AgentStreamEvent.ToolResult("call-1", "read_file", "hello", AgentStreamEvent.ToolStatus.COMPLETED));
        run.recordModelEvent(new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT).textContent("最终结论").build()),
                new ModelIdentity("test", "model"));

        List<TranscriptMessageDto.MessageBlockDto> blocks = run.finalizeBlocks(TranscriptMessageDto.MessageStatus.COMPLETED);
        assertEquals(3, blocks.size());
        assertEquals("我先查看文件。", blocks.get(0).content());
        assertEquals(TranscriptMessageDto.BlockKind.TOOL_GROUP, blocks.get(1).kind());
        assertEquals("最终结论", blocks.get(2).content());
    }

    @Test
    void finalizeBlocksMarksInterruptedRunningToolsAsCancelled() {
        AgentRun run = new AgentRun("session-3", "owner", "turn-3", null);
        run.record(new AgentStreamEvent.ToolCall("call-1", "web_search", "{\"query\":\"x\"}"));

        List<TranscriptMessageDto.MessageBlockDto> blocks = run.finalizeBlocks(TranscriptMessageDto.MessageStatus.CANCELLED);
        assertEquals(1, blocks.size());
        assertEquals(TranscriptMessageDto.ToolStatus.CANCELLED, blocks.get(0).tools().get(0).status());
    }

    @Test
    void transcriptRoundTripsBlocksAndReadsLegacyLinesWithoutBlocks() throws Exception {
        AgentStorageProperties storage = new AgentStorageProperties(tempDir);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TranscriptService transcript = new TranscriptService(storage, mapper);

        Path legacyFile = storage.transcriptFile("legacy-session");
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile,
                "{\"id\":\"m1\",\"turnId\":\"t1\",\"role\":\"ASSISTANT\",\"content\":\"旧回复\",\"createdAt\":\"2026-11-16T00:00:00Z\",\"status\":\"COMPLETED\",\"durationMillis\":1000,\"tools\":[],\"thinking\":null,\"usage\":null}\n");

        List<TranscriptMessageDto> legacyList = transcript.list("legacy-session");
        assertEquals(1, legacyList.size());
        assertTrue(legacyList.get(0).blocks().isEmpty());

        transcript.appendAssistantMessage(
                "new-session",
                "t2",
                "最终总结",
                "思考内容",
                TranscriptMessageDto.MessageStatus.COMPLETED,
                1200L,
                List.of(),
                null,
                List.of(
                        TranscriptMessageDto.MessageBlockDto.thinking("思考内容"),
                        TranscriptMessageDto.MessageBlockDto.text("先执行检索。"),
                        TranscriptMessageDto.MessageBlockDto.toolGroup(List.of(
                                new TranscriptMessageDto.ToolExecutionDto("c1", "web_search", "q", "ok", TranscriptMessageDto.ToolStatus.COMPLETED)
                        )),
                        TranscriptMessageDto.MessageBlockDto.text("最终总结")
                )
        );

        List<TranscriptMessageDto> newList = transcript.list("new-session");
        assertEquals(1, newList.size());
        assertEquals(4, newList.get(0).blocks().size());
        assertEquals(TranscriptMessageDto.BlockKind.TOOL_GROUP, newList.get(0).blocks().get(2).kind());
        assertEquals("最终总结", newList.get(0).blocks().get(3).content());
    }
}
