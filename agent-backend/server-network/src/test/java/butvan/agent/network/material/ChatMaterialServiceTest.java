package butvan.agent.network.material;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.material.ChatMaterialRecorder;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.config.database.LocalDatabaseProperties;
import butvan.agent.network.file.repository.FileAssetRepository;
import butvan.agent.network.file.service.FileAssetService;
import butvan.agent.network.file.storage.LocalBlobStore;
import butvan.agent.network.material.repository.ChatMaterialRepository;
import butvan.agent.network.material.service.ChatMaterialService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证对话档案（素材）的唯一 ID 入库、按渠道/会话/格式/日期多级目录归档、读取权限与删除清理。 */
class ChatMaterialServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void recordsImagesInCategorizedDirectoriesWithUniqueIdsAndSupportsQueryAndDelete() throws Exception {
        Path root = tempDir.resolve(".butvan-agent");
        Path dbPath = root.resolve("data/butvan.db");
        Files.createDirectories(dbPath.getParent());

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        LocalDatabaseProperties dbProps = new LocalDatabaseProperties();
        dbProps.setPath(dbPath);
        AgentStorageProperties storageProps = new AgentStorageProperties(root);

        FileAssetService fileAssetService = new FileAssetService(
                new FileAssetRepository(jdbc), new LocalBlobStore(dbProps, storageProps));
        ChatMaterialService service = new ChatMaterialService(fileAssetService, new ChatMaterialRepository(jdbc));

        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        List<String> fileIds = service.recordUserImages(new ChatMaterialRecorder.SaveImagesCommand(
                "local-default",
                "DESKTOP",
                "PROJECT",
                "session-abc-123",
                "proj-1",
                "项目排查",
                "11111111-1111-1111-1111-111111111111",
                List.of(new AgentImageAttachment("image/png", pngBytes))
        ));

        assertEquals(1, fileIds.size());
        String fileId = fileIds.getFirst();
        assertTrue(fileId.matches("[a-f0-9]{32}"));

        var item = service.get("local-default", fileId);
        assertEquals("DESKTOP", item.channel());
        assertEquals("PROJECT", item.sessionKind());
        assertEquals("session-abc-123", item.sessionId());
        assertEquals("IMAGE", item.category());
        assertEquals("PNG", item.subType());
        assertTrue(item.archivePath().startsWith("desktop/project/session-abc-123/images/png/"));
        assertTrue(item.archivePath().endsWith("/" + fileId + ".png"));

        Path physical = storageProps.getMaterialsDirectory().resolve(item.archivePath());
        assertTrue(Files.isRegularFile(physical));
        assertArrayEquals(pngBytes, Files.readAllBytes(physical));

        var page = service.list("local-default", "IMAGE", "PNG", "DESKTOP", "PROJECT",
                "session-abc-123", null, null, "项目排查", 20, 0);
        assertEquals(1, page.total());
        assertEquals(fileId, page.items().getFirst().fileId());

        assertEquals(1, service.summary("local-default").totalCount());
        assertThrows(IllegalArgumentException.class, () -> service.get("other-user", fileId));

        try (var stream = service.openContent("local-default", fileId).getInputStream()) {
            assertArrayEquals(pngBytes, stream.readAllBytes());
        }

        service.delete("local-default", fileId);
        assertFalse(Files.exists(physical));
        assertEquals(0, service.list("local-default", null, null, null, null, null, null, null, null, 20, 0).total());
    }
}
