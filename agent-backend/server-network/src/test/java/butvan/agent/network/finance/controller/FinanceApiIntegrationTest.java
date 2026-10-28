package butvan.agent.network.finance.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.finance.repository.FinanceRepository;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasItem;

/** 通过 HTTP seam 验证财务账户与流水接口。 */
@SpringBootTest(classes = FinanceApiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class FinanceApiIntegrationTest {

    private static final Path DATABASE_PATH = createDatabasePath();

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void apiCreatesAccountAndIncomeTransaction() throws Exception {
        // 月度接口使用实际当前月；测试流水与修改后的日期保持在同月，避免跨月后断言失效。
        LocalDate today = LocalDate.now();
        String transactionDate = today.withDayOfMonth(1).toString();
        String correctedDate = today.toString();
        String accountJson = mockMvc.perform(post("/agent/finance/accounts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "支付宝余额宝",
                                  "accountType": "alipay",
                                  "currency": "CNY",
                                  "initialBalance": 1000.00,
                                  "interestEnabled": true,
                                  "annualRatePercent": 1.85
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("支付宝余额宝"))
                .andReturn().getResponse().getContentAsString();
        String accountId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accountJson).path("data").path("id").asText();

        String transactionJson = mockMvc.perform(post("/agent/finance/transactions")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": "%s",
                                  "transactionType": "income",
                                  "category": "稿费",
                                  "categoryColor": "#3b82f6",
                                  "note": "本月稿费",
                                  "amount": 500.00,
                                  "date": "%s",
                                  "time": "09:00"
                                }
                                """.formatted(accountId, transactionDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionType").value("income"))
                .andReturn().getResponse().getContentAsString();
        String transactionId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(transactionJson).path("data").path("id").asText();

        mockMvc.perform(get("/agent/finance/transactions/day").param("date", transactionDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(transactionId))
                .andExpect(jsonPath("$.data[0].accountId").value(accountId));
        mockMvc.perform(get("/agent/finance/transactions/day").param("date", "2000-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/agent/finance/cashflow-summary")
                        .param("from", transactionDate).param("to", transactionDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.income").value(500.00))
                .andExpect(jsonPath("$.data.expense").value(0));

        mockMvc.perform(get("/agent/finance/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.income", hasItem("稿费")))
                .andExpect(jsonPath("$.data.colors.income.稿费").value("#3B82F6"))
                .andExpect(jsonPath("$.data.colors.expense").isMap())
                .andExpect(jsonPath("$.data.expense").isArray());

        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAssets").value(1500.00))
                .andExpect(jsonPath("$.data.accounts[0].name").value("支付宝余额宝"));

        mockMvc.perform(get("/agent/finance/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].note").value("本月稿费"))
                .andExpect(jsonPath("$.data[0].accountName").value("支付宝余额宝"));

        mockMvc.perform(get("/agent/finance/expense-chart").param("range", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("month"))
                .andExpect(jsonPath("$.data.from").value(transactionDate))
                .andExpect(jsonPath("$.data.to").value(correctedDate))
                .andExpect(jsonPath("$.data.totalIncome").value(500.00))
                .andExpect(jsonPath("$.data.categoryColors").isMap())
                .andExpect(jsonPath("$.data.days").isArray());

        mockMvc.perform(get("/agent/finance/expense-chart").param("range", "today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("today"))
                .andExpect(jsonPath("$.data.from").value(correctedDate))
                .andExpect(jsonPath("$.data.to").value(correctedDate));

        // 创建第二个账户并验证划账接口
        String secondAccountJson = mockMvc.perform(post("/agent/finance/accounts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "微信零钱",
                                  "accountType": "wechat_balance",
                                  "currency": "CNY",
                                  "initialBalance": 200.00,
                                  "interestEnabled": false,
                                  "annualRatePercent": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondAccountId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(secondAccountJson).path("data").path("id").asText();

        mockMvc.perform(put("/agent/finance/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": "%s",
                                  "transactionType": "income",
                                  "category": "稿费",
                                  "note": "修正后的本月稿费",
                                  "amount": 500.00,
                                  "date": "%s",
                                  "time": "10:30"
                                }
                                """.formatted(secondAccountId, correctedDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(secondAccountId))
                .andExpect(jsonPath("$.data.accountName").value("微信零钱"))
                .andExpect(jsonPath("$.data.date").value(correctedDate))
                .andExpect(jsonPath("$.data.note").value("修正后的本月稿费"));

        mockMvc.perform(get("/agent/finance/categories"))
                .andExpect(jsonPath("$.data.colors.income.稿费").value("#3B82F6"));

        mockMvc.perform(put("/agent/finance/transactions/{transactionId}", transactionId)
                        .contentType("application/json")
                        .content("""
                                {"accountId":"%s","transactionType":"income","category":"稿费",
                                 "categoryColor":"#bad","note":"无效颜色","amount":500,
                                 "date":"%s","time":"10:30"}
                                """.formatted(secondAccountId, correctedDate)))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/agent/finance/categories"))
                .andExpect(jsonPath("$.data.colors.income.稿费").value("#3B82F6"));

        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts[0].balance").value(1000.00))
                .andExpect(jsonPath("$.data.accounts[1].balance").value(700.00));

        mockMvc.perform(post("/agent/finance/transfers")
                        .contentType("application/json")
                        .content("""
                                {
                                  "fromAccountId": "%s",
                                  "toAccountId": "%s",
                                  "amount": 350.00,
                                  "note": "日常充值零钱",
                                  "date": "%s",
                                  "time": "11:20"
                                }
                                """.formatted(accountId, secondAccountId, correctedDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromTransaction.transactionType").value("transfer_out"))
                .andExpect(jsonPath("$.data.fromTransaction.amount").value(350.00))
                .andExpect(jsonPath("$.data.toTransaction.transactionType").value("transfer_in"))
                .andExpect(jsonPath("$.data.toTransaction.amount").value(350.00));

        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAssets").value(1700.00));

        mockMvc.perform(post("/agent/finance/accounts/{accountId}/adjustments", secondAccountId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "direction": "increase",
                                  "amount": 25.00,
                                  "note": "余额对账补差"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionType").value("adjustment_increase"))
                .andExpect(jsonPath("$.data.source").value("adjustment"))
                .andExpect(jsonPath("$.data.category").value("余额校准"))
                .andExpect(jsonPath("$.data.note").value("余额对账补差"));

        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAssets").value(1725.00))
                .andExpect(jsonPath("$.data.monthIncome").value(500.00))
                .andExpect(jsonPath("$.data.monthExpense").value(0));
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-finance-api-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建财务接口测试数据库目录", exception);
        }
    }

    /** 仅装配财务 HTTP 测试所需依赖。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            LocalDatabaseConfiguration.class,
            FinanceRepository.class,
            DailyEventModuleConfiguration.class,
            FinanceService.class,
            FinanceController.class,
            ApiExceptionHandler.class,
            CurrentUserProvider.class
    })
    static class TestApplication {
    }
}
