package butvan.agent.agents.agent.permission;

import butvan.agent.agents.agent.run.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 管理未完成审批及“仅当前会话”的精确授权记忆。 */
@Component
public class PendingApprovalStore {
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final Map<SessionKey, String> sessionApprovals = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionDecisions = new ConcurrentHashMap<>();
    private final ObjectMapper canonicalJson = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public synchronized void save(PendingApproval approval) {
        SessionKey sessionKey = new SessionKey(approval.run().userId(), approval.run().sessionId());
        approvals.put(approval.approvalId(), approval);
        String existing = sessionApprovals.putIfAbsent(sessionKey, approval.approvalId());
        if (existing != null) {
            approvals.remove(approval.approvalId());
            throw new IllegalArgumentException("当前会话已有操作等待确认");
        }
    }

    /** 同时校验 approvalId、用户和会话，不能只相信前端传来的 UUID。 */
    public PendingApproval require(String approvalId, String userId, String sessionId) {
        PendingApproval approval = Optional.ofNullable(approvals.get(approvalId))
                .orElseThrow(() -> new IllegalArgumentException("确认请求不存在或已失效"));
        AgentRun run = approval.run();
        if (!run.userId().equals(userId) || !run.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException("无权操作此确认请求");
        }
        return approval;
    }

    public void remember(String userId, String sessionId, ToolUseBlock tool, boolean approved) {
        sessionDecisions.put(key(userId, sessionId, tool), approved);
    }

    public Optional<Boolean> remembered(String userId, String sessionId, ToolUseBlock tool) {
        return Optional.ofNullable(sessionDecisions.get(key(userId, sessionId, tool)));
    }

    public synchronized void remove(String approvalId) {
        PendingApproval removed = approvals.remove(approvalId);
        if (removed != null) {
            sessionApprovals.remove(
                    new SessionKey(removed.run().userId(), removed.run().sessionId()), approvalId);
        }
    }

    /** 返回会话当前等待中的审批，供界面刷新后恢复。 */
    public synchronized Optional<PendingApprovalView> current(String userId, String sessionId) {
        String approvalId = sessionApprovals.get(new SessionKey(userId, sessionId));
        PendingApproval approval = approvalId == null ? null : approvals.get(approvalId);
        if (approval == null || !approval.isWaiting()) return Optional.empty();
        return Optional.of(PendingApprovalView.from(approval));
    }

    /** 服务端阻止绕过界面直接在 ASKING 会话中发起新轮次。 */
    public synchronized void requireNoPending(String userId, String sessionId) {
        if (sessionApprovals.containsKey(new SessionKey(userId, sessionId))) {
            throw new IllegalArgumentException("当前会话仍有操作等待确认，请先处理或拒绝该操作");
        }
    }

    /** 原子领取审批恢复权。 */
    public synchronized PendingApproval claimForResume(
            String approvalId, String userId, String sessionId, String runId) {
        PendingApproval approval = require(approvalId, userId, sessionId);
        approval.claimForResume(runId);
        remove(approvalId);
        return approval;
    }

    /** 原子领取取消权，不需要先批准工具，也不允许与恢复同时领取。 */
    public synchronized PendingApproval claimForCancellation(
            String approvalId, String userId, String sessionId, String runId) {
        PendingApproval approval = require(approvalId, userId, sessionId);
        if (!approval.runId().equals(runId)) throw new IllegalArgumentException("运行 ID 与审批不匹配");
        remove(approvalId);
        return approval;
    }

    /** 恢复线程启动失败时，把已领取批次放回等待态，允许客户端重试。 */
    public synchronized void restoreAfterFailedResume(PendingApproval approval) {
        approval.releaseResumeClaim();
        save(approval);
    }

    /** 判断某会话是否仍有未完成的权限确认。 */
    public boolean hasPending(String userId, String sessionId) {
        return sessionApprovals.containsKey(new SessionKey(userId, sessionId));
    }

    /** 会话被删除或用户显式清空会话时必须调用，避免内存长期累积。 */
    public synchronized void clearSession(String userId, String sessionId) {
        String prefix = userId + ":" + sessionId + ":";
        sessionDecisions.keySet().removeIf(key -> key.startsWith(prefix));
        approvals.entrySet().removeIf(entry -> {
            AgentRun run = entry.getValue().run();
            return run.userId().equals(userId) && run.sessionId().equals(sessionId);
        });
        sessionApprovals.remove(new SessionKey(userId, sessionId));
    }

    private String key(String userId, String sessionId, ToolUseBlock tool) {
        try {
            String json = canonicalJson.writeValueAsString(tool.getInput());
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((tool.getName() + "\n" + json).getBytes(StandardCharsets.UTF_8));
            return userId + ":" + sessionId + ":" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成工具授权指纹", exception);
        }
    }

    private record SessionKey(String userId, String sessionId) {}
}
