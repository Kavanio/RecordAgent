package butvan.agent.agents.session;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.project.ProjectRegistry;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionCatalogServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedTitleNeverOverridesManualRenameAndPermissionPersists() {
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory.resolve("data-1"));
        CurrentUserProvider user = new CurrentUserProvider();
        SessionCatalogService service = new SessionCatalogService(
                storage,
                user,
                new ObjectMapper().findAndRegisterModules()
        );

        SessionSummaryDto created = service.create(new CreateSessionRequest(SessionKind.GENERAL, "新对话"));
        service.updateTitle(created.id(), "我手动设置的标题");
        SessionSummaryDto result = service.updateGeneratedTitle(created.id(), "模型生成标题");
        service.updatePermissionMode(created.id(), SessionPermissionMode.AUTO_EDIT);

        assertEquals("我手动设置的标题", result.title());
        assertEquals(SessionPermissionMode.AUTO_EDIT, service.getPermissionMode(created.id()));
    }

    @Test
    void defaultSessionAcceptsGeneratedTitleOnlyOnce() {
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory.resolve("data-2"));
        CurrentUserProvider user = new CurrentUserProvider();
        SessionCatalogService service = new SessionCatalogService(
                storage,
                user,
                new ObjectMapper().findAndRegisterModules()
        );

        SessionSummaryDto created = service.create(new CreateSessionRequest(SessionKind.GENERAL, "新对话"));
        assertEquals("首个模型标题", service.updateGeneratedTitle(created.id(), "首个模型标题").title());
        assertEquals("首个模型标题", service.updateGeneratedTitle(created.id(), "第二个模型标题").title());
        assertEquals(SessionPermissionMode.ASK, service.getPermissionMode(created.id()));
    }

    @Test
    void projectSessionRequiresRegisteredProjectAndPersistsBinding() throws Exception {
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory.resolve("data-3"));
        CurrentUserProvider user = new CurrentUserProvider();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProjectRegistry projects = new ProjectRegistry(storage, user, mapper);
        SessionCatalogService service = new SessionCatalogService(storage, user, mapper, projects);
        Path root = Files.createDirectories(temporaryDirectory.resolve("projects/demo"));
        String projectId = projects.importProject("demo", root.toString()).id();

        SessionSummaryDto created = service.create(
                new CreateSessionRequest(SessionKind.PROJECT, "项目会话", projectId));

        assertEquals(SessionKind.PROJECT, created.kind());
        assertEquals(projectId, created.projectId());
        assertEquals(projectId, service.listActive().getFirst().projectId());
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateSessionRequest(SessionKind.PROJECT, "错误会话", "missing")));
    }
    @Test
    void channelPersistsAcrossRenamingPermissionsAndPreviewUpdates() {
        var storage = new AgentStorageProperties(temporaryDirectory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var user = new CurrentUserProvider();
        var catalog = new SessionCatalogService(storage, user, mapper);
        var desktop = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "飞书对话"));
        var feishu = catalog.createFeishuSession();
        catalog.updateTitle(feishu.id(), "重命名");
        catalog.updatePermissionMode(feishu.id(), SessionPermissionMode.FULL_ACCESS);
        catalog.touch(feishu.id(), "回复内容");
        // 迁移不允许把带明确桌面标记的新会话改成飞书渠道。
        catalog.migrateFeishuSessions(java.util.Set.of(desktop.id()));
        var reloaded = new SessionCatalogService(storage, user, mapper);
        assertEquals(butvan.agent.agents.session.dto.SessionChannel.FEISHU, reloaded.getChannel(feishu.id()));
        assertEquals(butvan.agent.agents.session.dto.SessionChannel.DESKTOP, reloaded.getChannel(desktop.id()));
        assertEquals("回复内容", reloaded.requireActive(feishu.id()).lastMessagePreview());
        assertEquals(SessionPermissionMode.FULL_ACCESS, reloaded.getPermissionMode(feishu.id()));
    }

}
