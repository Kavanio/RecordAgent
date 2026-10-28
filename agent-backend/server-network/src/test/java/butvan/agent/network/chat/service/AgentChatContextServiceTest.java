package butvan.agent.network.chat.service;

import butvan.agent.network.chat.dto.AgentChatRequest;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.record.model.RecordModels.RecordCommand;
import butvan.agent.network.record.model.RecordModels.RecordType;
import butvan.agent.network.record.service.RecordService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实临时数据库验证稳定资料引用到 Agent 上下文的转换 seam。 */
@SpringBootTest(classes = AgentChatContextServiceTest.TestApplication.class)
class AgentChatContextServiceTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @jakarta.annotation.Resource private RecordService recordService;
    @jakarta.annotation.Resource private AgentChatContextService contextService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void resolvesStableRecordIdAndSeparatesDisplayContentFromRagContext() {
        var record = recordService.create("owner", new RecordCommand(
                LocalDate.of(2026, 9, 11), RecordType.READING, "架构资料",
                "<p>资料正文</p>", "资料正文", List.of("架构"), null));

        var call = contextService.prepare("owner", new AgentChatRequest(
                "session-1", "/ask-record 这篇资料讲了什么？", "这篇资料讲了什么？", List.of(record.id()), null));

        assertEquals("/ask-record 这篇资料讲了什么？", call.content());
        assertEquals(List.of("资料正文"), call.ragContexts());
        assertTrue(call.context().contains("资料 ID：" + record.id()));
        assertTrue(call.context().contains("资料编号：[1]"));
        assertTrue(call.context().contains("回答末尾增加“引用资料”"));
        assertTrue(call.context().contains("用户问题：这篇资料讲了什么？"));
    }

    @Test
    void keepsOrdinaryChatRequestsUnexpanded() {
        var call = contextService.prepare("owner",
                new AgentChatRequest("session-1", "你好", "你好", List.of(), null));

        assertEquals("你好", call.context());
        assertTrue(call.ragContexts().isEmpty());
    }

    @Test
    void sharesContextBudgetAcrossComparedRecords() {
        String firstContent = "甲".repeat(30_000);
        String secondContent = "乙".repeat(30_000);
        var first = recordService.create("owner", new RecordCommand(
                LocalDate.of(2026, 9, 8), RecordType.READING, "第一篇",
                "<p>第一篇</p>", firstContent, List.of(), null));
        var second = recordService.create("owner", new RecordCommand(
                LocalDate.of(2026, 9, 9), RecordType.READING, "第二篇",
                "<p>第二篇</p>", secondContent, List.of(), null));

        var call = contextService.prepare("owner", new AgentChatRequest(
                "session-1", "/compare-records", "请比较两篇资料", List.of(first.id(), second.id()), null));

        assertEquals(2, call.ragContexts().size());
        assertEquals(25_000, call.ragContexts().get(0).length());
        assertEquals(25_000, call.ragContexts().get(1).length());
        assertTrue(call.context().contains("资料标题：第一篇"));
        assertTrue(call.context().contains("资料标题：第二篇"));
        assertTrue(call.context().contains("资料编号：[2]"));
    }

    @Test
    void rejectsReferencesThatAreNoLongerVisible() {
        var record = recordService.create("owner", new RecordCommand(
                LocalDate.of(2026, 9, 10), RecordType.QUICK, "已归档资料",
                "<p>旧正文</p>", "旧正文", List.of(), null));
        recordService.updateFlags("owner", record.id(), record.version(), null, null, true);

        assertThrows(IllegalArgumentException.class, () -> contextService.prepare("owner",
                new AgentChatRequest("session-1", "/ask-record 问题", "问题", List.of(record.id()), null)));
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-chat-context-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建聊天上下文测试数据库目录", exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            LocalDatabaseConfiguration.class,
            DailyEventModuleConfiguration.class,
            RecordService.class,
            AgentChatContextService.class,
        butvan.agent.agents.model.ModelHolder.class
    })
    static class TestApplication {
    }
}
