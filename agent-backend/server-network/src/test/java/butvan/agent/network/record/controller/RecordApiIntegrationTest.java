package butvan.agent.network.record.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.file.repository.FileAssetRepository;
import butvan.agent.network.file.service.FileAssetService;
import butvan.agent.network.file.storage.LocalBlobStore;
import butvan.agent.network.record.repository.RecordRepository;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.record.service.RecordAttachmentService;
import butvan.agent.network.record.service.RecordBackupService;
import butvan.agent.network.record.service.RecordTabService;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.daily.model.DailyEventModels.JournalDetails;
import butvan.agent.network.daily.model.DailyEventModels.JournalCommand;
import butvan.agent.network.daily.service.DailyEventService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过公开 HTTP seam 验证记录创建、检索、周复盘和回收站行为。 */
@SpringBootTest(classes = RecordApiIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class RecordApiIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();
    @jakarta.annotation.Resource private MockMvc mockMvc;
    @jakarta.annotation.Resource private DailyEventService dailyEventService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) { registry.add("butvan.database.path", DATABASE_PATH::toString); }

    @Test
    void listsLightweightReferenceOptionsAndFiltersByContent() throws Exception {
        mockMvc.perform(post("/agent/records").contentType("application/json").content("""
                {"recordDate":"2026-09-11","type":"reading","title":"Slash Command 架构",
                 "contentHtml":"<p>引用选择器应保存稳定 ID</p>","contentText":"引用选择器应保存稳定 ID",
                 "tags":["命令框架"]}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/agent/records/references").param("query", "稳定 ID").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextOffset").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Slash Command 架构"))
                .andExpect(jsonPath("$.data.items[0].summary").value("引用选择器应保存稳定 ID"))
                .andExpect(jsonPath("$.data.items[0].contentText").doesNotExist());
    }

    @Test
    void pagesReferenceOptionsWithoutLoadingFullContent() throws Exception {
        for (int index = 1; index <= 2; index++) {
            mockMvc.perform(post("/agent/records").contentType("application/json").content("""
                    {"recordDate":"2026-09-10","type":"quick","title":"分页候选 %d",
                     "contentHtml":"<p>分页候选正文</p>","contentText":"分页候选正文","tags":[]}
                    """.formatted(index))).andExpect(status().isOk());
        }

        mockMvc.perform(get("/agent/records/references")
                        .param("query", "分页候选").param("limit", "1").param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextOffset").value(1));
        mockMvc.perform(get("/agent/records/references")
                        .param("query", "分页候选").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextOffset").value(2));
    }

    @Test
    void createsSearchesAndTrashesWeeklyReview() throws Exception {
        String response = mockMvc.perform(post("/agent/records").contentType("application/json").content("""
                {"recordDate":"2026-09-04","type":"weekly_review","title":"第 36 周复盘",
                 "contentHtml":"<p>完成记录模块设计</p>","contentText":"完成记录模块设计","tags":["产品","复盘"]}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekYear").value(2026))
                .andExpect(jsonPath("$.data.weekNumber").value(36))
                .andExpect(jsonPath("$.data.tags[0]").value("产品"))
                .andReturn().getResponse().getContentAsString();
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data");
        String recordId = node.path("id").asText();

        mockMvc.perform(multipart("/agent/records/" + recordId + "/attachments")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "附件内容".getBytes())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.originalName").value("notes.txt"));

        mockMvc.perform(get("/agent/records").param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("query", "记录模块"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/agent/records/days").param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].weeklyReviewCompleted").value(true));

        String tabJson = mockMvc.perform(post("/agent/records/tabs").contentType("application/json")
                        .content("{\"name\":\"Spring 专题\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.systemKey").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String customTabId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tabJson).path("data").path("id").asText();
        mockMvc.perform(post("/agent/records").contentType("application/json").content("""
                {"recordDate":"2026-09-04","type":"learning","title":"Spring 循环依赖",
                 "contentHtml":"<p>三级缓存</p>","contentText":"三级缓存","tags":[],"tabId":"%s"}
                """.formatted(customTabId))).andExpect(status().isOk()).andExpect(jsonPath("$.data.tabId").value(customTabId));
        mockMvc.perform(get("/agent/records").param("from", "2026-09-01").param("to", "2026-09-30").param("tabId", customTabId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(delete("/agent/records/" + recordId)
                        .param("expectedVersion", node.path("version").asText()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/agent/records/trash"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));

        byte[] backup = mockMvc.perform(get("/agent/records/backup"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        mockMvc.perform(multipart("/agent/records/backup")
                        .file(new MockMultipartFile("file", "backup.zip", "application/zip", backup)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(2));
        mockMvc.perform(get("/agent/records/trash"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].type").value("weekly_review"));
        mockMvc.perform(delete("/agent/records/trash"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(get("/agent/records/trash"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void uploadsDownloadsAndDeletesAttachmentThroughFileAssetModule() throws Exception {
        String recordResponse = mockMvc.perform(post("/agent/records").contentType("application/json").content("""
                {"recordDate":"2026-09-20","type":"quick","title":"文件资产测试",
                 "contentHtml":"<p>附件生命周期</p>","contentText":"附件生命周期","tags":[]}
                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String recordId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(recordResponse)
                .path("data").path("id").asText();
        byte[] content = "附件内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String attachmentResponse = mockMvc.perform(multipart("/agent/records/" + recordId + "/attachments")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("notes.txt"))
                .andReturn().getResponse().getContentAsString();
        String attachmentId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(attachmentResponse)
                .path("data").path("id").asText();

        byte[] downloaded = mockMvc.perform(get("/agent/records/" + recordId + "/attachments/"
                        + attachmentId + "/content"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(content, downloaded);

        mockMvc.perform(delete("/agent/records/" + recordId + "/attachments/" + attachmentId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/agent/records/" + recordId + "/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void reordersAllRecordTabsAndPersistsTheResult() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var originalTabs = mapper.readTree(mockMvc.perform(get("/agent/records/tabs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var reversedIds = new ArrayList<String>();
        for (int index = originalTabs.size() - 1; index >= 0; index--) reversedIds.add(originalTabs.get(index).path("id").asText());

        mockMvc.perform(put("/agent/records/tabs/order").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("tabIds", reversedIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(reversedIds.getFirst()));

        mockMvc.perform(get("/agent/records/tabs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(reversedIds.getFirst()))
                .andExpect(jsonPath("$.data[0].sortOrder").value(10));
    }

    @Test
    void recordJournalIsVisibleInCalendarAndRemovedWithItsSourceRecord() throws Exception {
        String response = mockMvc.perform(post("/agent/records").contentType("application/json").content("""
                {"recordDate":"2026-10-01","type":"journal","title":"十月手记",
                 "contentHtml":"<p>今天完成了资料整理。</p>","contentText":"今天完成了资料整理。","tags":[]}
                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data");

        mockMvc.perform(get("/agent/records").param("from", "2026-10-01").param("to", "2026-10-01")
                        .param("type", "journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("十月手记"));

        var synced = dailyEventService.getDay("local-default", java.time.LocalDate.of(2026, 10, 1))
                .events().stream().filter(event -> "record".equals(event.source())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("十月手记", synced.title());
        org.junit.jupiter.api.Assertions.assertEquals("今天完成了资料整理。",
                ((JournalDetails) synced.details()).body());

        String updatedResponse = mockMvc.perform(put("/agent/records/" + node.path("id").asText())
                        .param("expectedVersion", node.path("version").asText())
                        .contentType("application/json").content("""
                                {"recordDate":"2026-10-02","type":"journal","title":"十月手记补充",
                                 "contentHtml":"<p>补充了第二版。</p>","contentText":"补充了第二版。","tags":[]}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var updatedNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(updatedResponse).path("data");
        var updatedJournal = dailyEventService.getDay("local-default", java.time.LocalDate.of(2026, 10, 2))
                .events().stream().filter(event -> "record".equals(event.source())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("十月手记补充", updatedJournal.title());
        org.junit.jupiter.api.Assertions.assertTrue(
                dailyEventService.getDay("local-default", java.time.LocalDate.of(2026, 10, 1)).events().isEmpty());

        mockMvc.perform(delete("/agent/records/" + node.path("id").asText())
                        .param("expectedVersion", updatedNode.path("version").asText()))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(
                dailyEventService.getDay("local-default", java.time.LocalDate.of(2026, 10, 2)).events().isEmpty());
    }

    @Test
    void calendarJournalIsVisibleAndEditableInRecords() throws Exception {
        var created = dailyEventService.create("local-default", new JournalCommand(
                java.time.LocalDate.of(2026, 11, 1), "日历手记", "从日历写下的正文", "平静"));

        String response = mockMvc.perform(get("/agent/records").param("from", "2026-11-01").param("to", "2026-11-01")
                        .param("type", "journal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("日历手记"))
                .andExpect(jsonPath("$.data[0].source").value("calendar"))
                .andReturn().getResponse().getContentAsString();
        var record = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").get(0);

        mockMvc.perform(put("/agent/records/" + record.path("id").asText())
                        .param("expectedVersion", record.path("version").asText())
                        .contentType("application/json").content("""
                                {"recordDate":"2026-11-02","type":"journal","title":"记录页补充",
                                 "contentHtml":"<p>双向同步后的正文</p>","contentText":"双向同步后的正文","tags":[]}
                                """))
                .andExpect(status().isOk());

        var synced = dailyEventService.getDay("local-default", java.time.LocalDate.of(2026, 11, 2))
                .events().stream().filter(event -> event.id().equals(created.id())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("记录页补充", synced.title());
        org.junit.jupiter.api.Assertions.assertEquals("双向同步后的正文", ((JournalDetails) synced.details()).body());
    }

    private static Path createDatabasePath() {
        try { return Files.createTempDirectory("butvan-record-api-test-").resolve("butvan.db"); }
        catch (IOException exception) { throw new IllegalStateException("无法创建记录接口测试数据库目录", exception); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({LocalDatabaseConfiguration.class, DailyEventModuleConfiguration.class, RecordRepository.class, RecordService.class,
            RecordAttachmentService.class, RecordBackupService.class, RecordTabService.class, FileAssetRepository.class,
            FileAssetService.class, LocalBlobStore.class,
            RecordController.class, ApiExceptionHandler.class, CurrentUserProvider.class})
    static class TestApplication {
        /** 文件存储新增的素材根目录同样隔离在测试临时目录，禁止回落到真实用户目录。 */
        @Bean
        AgentStorageProperties agentStorageProperties() {
            return new AgentStorageProperties(DATABASE_PATH.getParent().resolve("agent-storage"));
        }
    }
}
