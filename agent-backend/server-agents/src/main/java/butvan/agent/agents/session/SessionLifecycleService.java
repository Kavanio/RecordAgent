package butvan.agent.agents.session;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionDetailDto;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.usage.SystemUsageLedger;
import butvan.agent.agents.usage.TokenUsageAggregator;
import io.agentscope.core.state.AgentStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 会话生命周期协调服务。
 *
 * <p>Controller 只调用本类；本类按固定顺序协调目录册、消息投影和 AgentScope 状态，
 * 使删除动作可理解且不污染 Controller。</p>
 */
@Service
@RequiredArgsConstructor
public class SessionLifecycleService {

    private final SessionCatalogService sessionCatalogService;
    private final TranscriptService transcriptService;
    private final CurrentUserProvider currentUserProvider;
    private final AgentStateStore agentStateStore;
    private final PendingApprovalStore pendingApprovalStore;
    private final SessionTitleService sessionTitleService;
    private final AgentRunCheckpointService checkpointService;
    private final SystemUsageLedger systemUsageLedger;


    public List<SessionSummaryDto> listSessions() {
        return sessionCatalogService.listActive(butvan.agent.agents.session.dto.SessionChannel.DESKTOP);
    }

    public SessionSummaryDto createSession(CreateSessionRequest request) {
        return sessionCatalogService.create(request);
    }

    public SessionDetailDto getDetail(String sessionId) {
        SessionSummaryDto summary = sessionCatalogService.requireDesktopActive(sessionId);
        return readDetail(summary);
    }

    /** 客户端独立分组只读列出飞书会话，不开放桌面续聊或修改入口。 */
    public List<SessionSummaryDto> listFeishuSessions() {
        return sessionCatalogService.listActive(butvan.agent.agents.session.dto.SessionChannel.FEISHU);
    }

    /** 按目录册校验飞书来源后读取原有 transcript。 */
    public SessionDetailDto getFeishuDetail(String sessionId) {
        SessionSummaryDto summary = sessionCatalogService.requireActive(sessionId);
        if (sessionCatalogService.getChannel(sessionId) != butvan.agent.agents.session.dto.SessionChannel.FEISHU) {
            throw new IllegalArgumentException("该会话不属于飞书渠道");
        }
        return readDetail(summary);
    }

    private SessionDetailDto readDetail(SessionSummaryDto summary) {
        List<TranscriptMessageDto> messages = transcriptService.list(summary.id());
        int turnCount = (int) messages.stream()
                .filter(message -> message.role() == TranscriptMessageDto.MessageRole.ASSISTANT)
                .count();
        var usages = messages.stream()
                .filter(message -> message.role() == TranscriptMessageDto.MessageRole.ASSISTANT)
                .map(TranscriptMessageDto::usage)
                .filter(Objects::nonNull)
                .toList();
        return new SessionDetailDto(
                summary,
                messages,
                TokenUsageAggregator.summarize(turnCount, usages)
        );
    }

    public SessionSummaryDto updateTitle(String sessionId, String title) {
        sessionCatalogService.requireDesktopActive(sessionId);
        return sessionCatalogService.updateTitle(sessionId, title);
    }

    public SessionSummaryDto generateTitle(String sessionId) {
        sessionCatalogService.requireDesktopActive(sessionId);
        return sessionTitleService.generateIfNeeded(sessionId);
    }

    public SessionPermissionMode getPermissionMode(String sessionId) {
        sessionCatalogService.requireDesktopActive(sessionId);
        return sessionCatalogService.getPermissionMode(sessionId);
    }

    public SessionPermissionMode updatePermissionMode(String sessionId, SessionPermissionMode mode) {
        sessionCatalogService.requireDesktopActive(sessionId);
        return sessionCatalogService.updatePermissionMode(sessionId, mode);
    }

    /**
     * 删除顺序必须固定：先阻止新请求，在删除应用消息，随后删除AgentState，最后一出目录册
     * @param sessionId
     */
    public void deleteSession(String sessionId) {
        sessionCatalogService.requireDesktopChannel(sessionId);
        sessionCatalogService.markDeleting(sessionId);
        checkpointService.deleteSession(sessionId);
        transcriptService.delete(sessionId);
        systemUsageLedger.deleteSession(sessionId);

        pendingApprovalStore.clearSession(currentUserProvider.currentUserId(), sessionId);
        agentStateStore.delete(currentUserProvider.currentUserId(), sessionId);
        sessionCatalogService.remove(sessionId);
    }
}
