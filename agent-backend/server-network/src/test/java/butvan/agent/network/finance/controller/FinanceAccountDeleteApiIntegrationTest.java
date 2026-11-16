package butvan.agent.network.finance.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.finance.repository.FinanceRepository;
import butvan.agent.network.finance.service.FinanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 通过 HTTP seam 验证资产账户删除接口要求余额为零并保留历史流水与统计。 */
@SpringBootTest(classes = FinanceAccountDeleteApiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class FinanceAccountDeleteApiIntegrationTest {

    private static final Path DATABASE_PATH = createDatabasePath();

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void deletingAccountRequiresZeroBalanceAndPreservesHistory() throws Exception {
        String accountJson = mockMvc.perform(post("/agent/finance/accounts")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "待停用银行卡",
                                  "accountType": "bank",
                                  "currency": "CNY",
                                  "initialBalance": 150.00,
                                  "interestEnabled": false,
                                  "annualRatePercent": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accountId = new ObjectMapper().readTree(accountJson).path("data").path("id").asText();

        mockMvc.perform(delete("/agent/finance/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/agent/finance/transactions")
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId": "%s",
                                  "transactionType": "expense",
                                  "category": "日常",
                                  "note": "历史消费",
                                  "amount": 100.00,
                                  "date": "%s",
                                  "time": "14:00"
                                }
                                """.formatted(accountId, LocalDate.now())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/agent/finance/accounts/{accountId}/adjustments", accountId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "direction": "decrease",
                                  "amount": 50.00,
                                  "note": "余额清零"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/agent/finance/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/agent/finance/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAssets").value(0))
                .andExpect(jsonPath("$.data.monthExpense").value(100.00))
                .andExpect(jsonPath("$.data.accounts").isEmpty())
                .andExpect(jsonPath("$.data.transactions.length()").value(2));

        mockMvc.perform(delete("/agent/finance/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-finance-account-delete-api-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建资产删除接口测试数据库目录", exception);
        }
    }

    /** 仅装配财务 HTTP 账户删除测试所需依赖。 */
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
