package butvan.agent.network.insight.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.daily.model.DailyEventModels.TodoCommand;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.finance.repository.FinanceRepository;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.insight.service.DailyInsightService;
import butvan.agent.network.record.model.RecordModels.RecordCommand;
import butvan.agent.network.record.model.RecordModels.RecordType;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.study.repository.StudyRepository;
import butvan.agent.network.study.service.StudyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过每日洞察的 HTTP seam 验证四个领域使用同一份确定性汇总。 */
@SpringBootTest(classes = DailyInsightApiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class DailyInsightApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();
    private static final LocalDate DATE = LocalDate.of(2026, 9, 11);
    private static final String OWNER = "local-default";

    @jakarta.annotation.Resource private MockMvc mockMvc;
    @jakarta.annotation.Resource private DailyEventService dailyEventService;
    @jakarta.annotation.Resource private RecordService recordService;
    @jakarta.annotation.Resource private FinanceService financeService;
    @jakarta.annotation.Resource private StudyService studyService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void summarizesTodoRecordExpenseAndStudyThroughOneInterface() throws Exception {
        var completedTodo = dailyEventService.create(OWNER,
                new TodoCommand(DATE, "完成每日洞察", null, "high", "none"));
        dailyEventService.setTodoCompleted(OWNER, completedTodo.id(), true, completedTodo.version(), DATE);
        dailyEventService.create(OWNER, new TodoCommand(DATE, "补充文档", null, "medium", "none"));

        recordService.create(OWNER, new RecordCommand(DATE, RecordType.LEARNING, "学习记录",
                "<p>完成聚合设计</p>", "完成聚合设计", List.of("架构"), null));

        var account = financeService.createAccount(OWNER, "现金账户", "cash", "CNY",
                new BigDecimal("100.00"), false, BigDecimal.ZERO);
        financeService.createTransaction(OWNER, account.id(), "expense", "餐饮", "午餐",
                new BigDecimal("12.34"), DATE, LocalTime.NOON);

        studyService.createManual(OWNER, "阅读架构资料", "阅读",
                Instant.parse("2026-09-11T01:00:00Z"), Instant.parse("2026-09-11T02:00:00Z"),
                ZoneId.of("Asia/Shanghai"), "书房");

        mockMvc.perform(get("/agent/insights/daily")
                        .param("date", DATE.toString())
                        .param("timezone", "Asia/Shanghai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-09-11"))
                .andExpect(jsonPath("$.data.todos.total").value(2))
                .andExpect(jsonPath("$.data.todos.completed").value(1))
                .andExpect(jsonPath("$.data.records.createdCount").value(1))
                .andExpect(jsonPath("$.data.finance.expenseCount").value(1))
                .andExpect(jsonPath("$.data.finance.expenseTotal").value(12.34))
                .andExpect(jsonPath("$.data.finance.expenseCategories.餐饮").value(12.34))
                .andExpect(jsonPath("$.data.study.durationSeconds").value(3600))
                .andExpect(jsonPath("$.data.study.sessionCount").value(1));

        mockMvc.perform(get("/agent/insights/profile")
                        .param("month", "2026-09")
                        .param("timezone", "Asia/Shanghai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month").value("2026-09"))
                .andExpect(jsonPath("$.data.totalAssetsInCents").value(8766))
                .andExpect(jsonPath("$.data.totalExpenseInCents").value(1234))
                .andExpect(jsonPath("$.data.totalRecordMinutes").value(60))
                .andExpect(jsonPath("$.data.documentCount").value(1))
                .andExpect(jsonPath("$.data.documentWordCount").value(6))
                .andExpect(jsonPath("$.data.monthlyCompletedTodos").value(1))
                .andExpect(jsonPath("$.data.monthlyTodoCount").value(2));
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-daily-insight-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建每日洞察测试数据库目录", exception);
        }
    }

    /** 只装配每日洞察 HTTP 测试所需依赖。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            LocalDatabaseConfiguration.class,
            DailyEventModuleConfiguration.class,
            RecordService.class,
            FinanceRepository.class,
            FinanceService.class,
            StudyRepository.class,
            StudyService.class,
            DailyInsightService.class,
            DailyInsightController.class,
            ApiExceptionHandler.class,
            CurrentUserProvider.class
    })
    static class TestApplication {
    }
}
