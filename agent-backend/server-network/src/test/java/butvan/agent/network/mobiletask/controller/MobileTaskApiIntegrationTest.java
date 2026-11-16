package butvan.agent.network.mobiletask.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.mobiletask.repository.MobileTaskRepository;
import butvan.agent.network.mobiletask.service.MobileTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证手机本地通知任务与送达观察回执接口的持久化、幂等和版本校验。 */
@SpringBootTest(classes = MobileTaskApiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class MobileTaskApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @jakarta.annotation.Resource private MockMvc mockMvc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void crudAndDeliveryObservationLifecycle() throws Exception {
        String createBody = """
                {
                  "id": "task-1",
                  "title": "喝水提醒",
                  "body": "起身活动并补充水分",
                  "recurrence": "daily",
                  "hour": 10,
                  "minute": 30,
                  "timeZone": "Asia/Shanghai",
                  "enabled": true,
                  "version": 0
                }
                """;
        mockMvc.perform(post("/agent/mobile-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("task-1"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(get("/agent/mobile-tasks/enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("task-1"));

        String updateBody = """
                {
                  "id": "task-1",
                  "title": "晚间复盘",
                  "body": "记录今日完成事项",
                  "recurrence": "weekly",
                  "hour": 21,
                  "minute": 0,
                  "weekday": 5,
                  "timeZone": "Asia/Shanghai",
                  "enabled": true,
                  "version": 1
                }
                """;
        mockMvc.perform(put("/agent/mobile-tasks/task-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.recurrence").value("weekly"));

        String deliveryBody = """
                {
                  "eventId": "evt-1",
                  "deliveredAt": "2026-09-11T13:00:00Z"
                }
                """;
        mockMvc.perform(post("/agent/mobile-tasks/task-1/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 相同 eventId 重复上报保持幂等
        mockMvc.perform(post("/agent/mobile-tasks/task-1/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/agent/mobile-tasks/task-1/deliveries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(delete("/agent/mobile-tasks/task-1").param("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-mobile-task-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试数据库目录", exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            LocalDatabaseConfiguration.class,
            MobileTaskRepository.class,
            MobileTaskService.class,
            MobileTaskController.class,
            ApiExceptionHandler.class,
            CurrentUserProvider.class
    })
    static class TestApplication {
    }
}
