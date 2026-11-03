package butvan.agent.agents.session;

import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.usage.SystemUsageLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 渠道校验必须保留删除中会话的失败后清理能力。 */
class SessionLifecycleServiceTest {
    @TempDir Path root;

    @Test void deletionCanResumeAfterTheSessionWasMarkedDeleting() {
        var user = new CurrentUserProvider();
        var catalog = new SessionCatalogService(new AgentStorageProperties(root), user,
                new ObjectMapper().findAndRegisterModules());
        String id = catalog.create(new CreateSessionRequest(SessionKind.GENERAL, "桌面会话")).id();
        catalog.markDeleting(id);
        var transcripts = mock(TranscriptService.class);
        var state = mock(AgentStateStore.class);
        var checkpoints = mock(AgentRunCheckpointService.class);
        var usage = mock(SystemUsageLedger.class);
        var lifecycle = new SessionLifecycleService(catalog, transcripts, user, state,
                new PendingApprovalStore(), mock(SessionTitleService.class), checkpoints, usage);

        lifecycle.deleteSession(id);

        verify(transcripts).delete(id);
        verify(checkpoints).deleteSession(id);
        verify(usage).deleteSession(id);
        verify(state).delete(user.currentUserId(), id);
        assertThrows(IllegalArgumentException.class, () -> catalog.getChannel(id));
    }
}
