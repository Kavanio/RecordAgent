package butvan.agent.network.finance.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.finance.repository.FinanceRepository;
import butvan.agent.network.finance.service.FinanceService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过 HTTP seam 验证流水删除接口会同步回滚账户余额。 */
@SpringBootTest(classes = FinanceDeleteApiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class FinanceDeleteApiIntegrationTest {

    private static final Path DATABASE_PATH = createDatabasePath();

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void deletingTransactionRollsBackBalanceAndRejectsMissingRecord() throws Exception {
        String accountId = createAccount();
        String expenseId = createExpense(accountId);
        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts[0].balance").value(890.00));

        mockMvc.perform(delete("/agent/finance/transactions/{transactionId}", expenseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts[0].balance").value(1000.00))
                .andExpect(jsonPath("$.data.monthExpense").value(0));
        mockMvc.perform(get("/agent/finance/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(delete("/agent/finance/transactions/{transactionId}", expenseId))
                .andExpect(jsonPath("$.code").value(400));
    }

    private String createAccount() throws Exception {
        String accountJson = mockMvc.perform(post("/agent/finance/accounts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "微信余额",
                                  "accountType": "wechat_balance",
                                  "currency": "CNY",
                                  "initialBalance": 1000.00,
                                  "interestEnabled": false,
                                  "annualRatePercent": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(accountJson).path("data").path("id").asText();
    }

    private String createExpense(String accountId) throws Exception {
        String transactionJson = mockMvc.perform(post("/agent/finance/transactions")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": "%s",
                                  "transactionType": "expense",
                                  "category": "餐饮",
                                  "note": "记错的午餐",
                                  "amount": 110.00,
                                  "date": "%s",
                                  "time": "12:12"
                                }
                                """.formatted(accountId, LocalDate.now())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(transactionJson).path("data").path("id").asText();
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-finance-delete-api-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建财务删除接口测试数据库目录", exception);
        }
    }

    /** 仅装配财务 HTTP 删除测试所需依赖。 */
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
