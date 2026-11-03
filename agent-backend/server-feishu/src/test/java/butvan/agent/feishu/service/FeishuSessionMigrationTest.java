package butvan.agent.feishu.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.SessionLifecycleService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionChannel;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 只使用临时数据验证停用渠道时的迁移、历史保留及损坏保护。 */
class FeishuSessionMigrationTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void migratesBindingsAndRetainedTasksWithoutGuessingTitlesOrChangingHistory() throws Exception {
        var storage = new AgentStorageProperties(root);
        var owner = new CurrentUserProvider();
        var catalog = new SessionCatalogService(storage, owner, mapper);
        var transcripts = new TranscriptService(storage, mapper);
        String bound = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "已改名")).id();
        String archived = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "旧飞书记录")).id();
        String desktop = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "飞书对话")).id();
        // 模拟旧版本目录册：没有 channel 字段。
        var records = mapper.readTree(storage.getSessionCatalogFile().toFile());
        records.forEach(record -> ((ObjectNode) record).remove("channel"));
        mapper.writeValue(storage.getSessionCatalogFile().toFile(), records);
        transcripts.appendUserMessage(bound, "原始聊天内容");
        byte[] original = Files.readAllBytes(storage.transcriptFile(bound));
        var namespace = Files.createDirectories(storage.getFeishuDirectory().resolve("old-app"));
        mapper.writeValue(namespace.resolve("state.json").toFile(), Map.of("version", 1,
                "bindings", Map.of("conversation", bound, "deleted", "missing"),
                "tasks", List.of(Map.of("sessionId", archived, "input", "不用于识别渠道"))));
        var migration = new FeishuSessionMigration(storage, catalog, new ResetBarrier(storage));
        migration.migrate();
        migration.migrate();

        var reloaded = new SessionCatalogService(storage, owner, mapper);
        assertEquals(SessionChannel.FEISHU, reloaded.getChannel(bound));
        assertEquals(SessionChannel.FEISHU, reloaded.getChannel(archived));
        assertEquals(SessionChannel.DESKTOP, reloaded.getChannel(desktop));
        assertEquals(3, reloaded.listActive().size());
        assertArrayEquals(original, Files.readAllBytes(storage.transcriptFile(bound)));
        var lifecycle = new SessionLifecycleService(reloaded, transcripts, owner, null, null, null, null, null);
        assertEquals(List.of(desktop), lifecycle.listSessions().stream().map(s -> s.id()).toList());
        assertThrows(IllegalArgumentException.class, () -> lifecycle.getDetail(bound));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.updateTitle(bound, "误修改"));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.updatePermissionMode(bound,
                butvan.agent.agents.session.dto.SessionPermissionMode.FULL_ACCESS));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.deleteSession(bound));
        assertArrayEquals(original, Files.readAllBytes(storage.transcriptFile(bound)));
        assertEquals("已改名", reloaded.requireActive(bound).title());
    }

    @Test
    void corruptLedgerDoesNotPartiallyMigrateOrLeakItsContent() throws Exception {
        var storage = new AgentStorageProperties(root);
        var catalog = new SessionCatalogService(storage, new CurrentUserProvider(), mapper);
        catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "保留"));
        byte[] original = Files.readAllBytes(storage.getSessionCatalogFile());
        var namespace = Files.createDirectories(storage.getFeishuDirectory().resolve("broken"));
        Files.writeString(namespace.resolve("state.json"), "{\"secret\":\"private-body\", invalid");
        var migration = new FeishuSessionMigration(storage, catalog, new ResetBarrier(storage));
        var failure = assertThrows(IllegalStateException.class, migration::migrate);
        assertFalse(failure.toString().contains("private-body"));
        assertNull(failure.getCause());
        assertArrayEquals(original, Files.readAllBytes(storage.getSessionCatalogFile()));
    }

    @Test
    void resetBarrierPreventsReadingAndMigration() throws Exception {
        var storage = new AgentStorageProperties(root);
        var catalog = new SessionCatalogService(storage, new CurrentUserProvider(), mapper);
        var barrier = new ResetBarrier(storage);
        barrier.begin("reset", "request", "local-default");
        var namespace = Files.createDirectories(storage.getFeishuDirectory().resolve("broken"));
        Files.writeString(namespace.resolve("state.json"), "invalid");
        assertDoesNotThrow(() -> new FeishuSessionMigration(storage, catalog, barrier).migrate());
    }
}
