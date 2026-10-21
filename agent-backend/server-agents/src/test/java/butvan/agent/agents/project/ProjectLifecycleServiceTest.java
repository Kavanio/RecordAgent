package butvan.agent.agents.project;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLifecycleServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void removingProjectKeepsDirectoryAndDetachesSessions() throws Exception {
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory.resolve("app-data"));
        CurrentUserProvider user = new CurrentUserProvider();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProjectRegistry registry = new ProjectRegistry(storage, user, mapper);
        SessionCatalogService sessions = new SessionCatalogService(storage, user, mapper, registry);
        ProjectLifecycleService lifecycle = new ProjectLifecycleService(registry, sessions);
        Path root = Files.createDirectories(temporaryDirectory.resolve("projects/demo"));
        ProjectSummary project = lifecycle.importProject("Demo", root.toString());
        sessions.create(new CreateSessionRequest(SessionKind.PROJECT, "项目会话", project.id()));

        lifecycle.removeProject(project.id());

        assertTrue(Files.isDirectory(root));
        assertTrue(registry.listProjects().isEmpty());
        assertEquals(SessionKind.GENERAL, sessions.listActive().getFirst().kind());
        assertNull(sessions.listActive().getFirst().projectId());
    }

    @Test
    void removesMovedProjectReferenceAndKeepsSessions() throws Exception {
        AgentStorageProperties storage = new AgentStorageProperties(temporaryDirectory.resolve("app-data"));
        CurrentUserProvider user = new CurrentUserProvider();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProjectRegistry registry = new ProjectRegistry(storage, user, mapper);
        SessionCatalogService sessions = new SessionCatalogService(storage, user, mapper, registry);
        ProjectLifecycleService lifecycle = new ProjectLifecycleService(registry, sessions);
        Path root = Files.createDirectories(temporaryDirectory.resolve("projects/moved"));
        ProjectSummary project = lifecycle.importProject("Moved", root.toString());
        sessions.create(new CreateSessionRequest(SessionKind.PROJECT, "移动前会话", project.id()));
        Files.delete(root);

        lifecycle.removeProject(project.id());

        assertTrue(registry.listProjects().isEmpty());
        assertEquals(SessionKind.GENERAL, sessions.listActive().getFirst().kind());
        assertNull(sessions.listActive().getFirst().projectId());
    }
}
