package butvan.agent.network.reset.service;

import butvan.agent.agents.reset.*;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.reset.dto.ResetDtos.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 单用户重置协调：凭证确认、持久化屏障、停稳、白名单清理、可续做步骤与结果。 */
@Slf4j
@Service
public class AgentResetService {
    public static final String SCOPE = "agent-reset-m1-v1";
    public static final String CONFIRMATION = "清除聊天与记忆";
    private final ResetBarrier barrier;
    private final AgentResetFiles files;
    private final CurrentUserProvider user;
    private final PendingApprovalStore approvals;
    private final ActiveAgentRunRegistry runs;
    private final List<ResetParticipant> participants;
    private final Clock clock;
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("agent-reset").factory());
    private boolean working;
    private boolean pendingRetry;
    private boolean closing;

    public AgentResetService(ResetBarrier barrier, AgentResetFiles files, CurrentUserProvider user,
                             PendingApprovalStore approvals, ActiveAgentRunRegistry runs,
                             List<ResetParticipant> participants, Clock automationClock) {
        this.barrier = barrier; this.files = files; this.user = user; this.approvals = approvals;
        this.runs = runs; this.participants = participants.stream().sorted(Comparator.comparing(ResetParticipant::id)).toList();
        this.clock = automationClock;
    }
    /** 只发短期凭证和静态范围，不停止任务、不接触外部服务。 */
    public synchronized Preview preview() {
        if (barrier.blocked()) throw new ResetRequestException(409, "已有重置任务，请查看当前进度");
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
        if (tickets.size() >= 32) tickets.clear();
        String token = UUID.randomUUID().toString();
        long expiry = clock.millis() + 300_000;
        tickets.put(token, new Ticket(user.currentUserId(), barrier.state().generation(), expiry));
        return new Preview(token, expiry, SCOPE, barrier.state().generation(),
                List.of("全部聊天、运行检查点和审批决定", "个人画像、提案、历史和长期记忆", "AgentScope 会话、任务、消息、计划和索引",
                        "当前用户飞书旧正文和绑定", "聊天 Token 投影、AI 观察、建议、反馈、事实快照与生成结果"),
                List.of("手记、资料、附件、待办、日程、财务和学习", "人工复盘目标、普通任务和已冻结报告副本",
                        "模型与飞书凭据、桌面偏好、非聊天系统用量", "SQLite 业务库、项目引用、真实项目与 Git worktree"),
                "仅重置当前实际连接后端；归属不明的工作区会阻止清理。运行时使用过时需正常重启后端续做；Hindsight 尚未接入，未执行远端清理。已发送消息与系统备份无法撤回。");
    }
    /** 凭证、身份、范围与代次一起核验；同一请求重复提交返回同一任务。 */
    public synchronized Status confirm(Confirm input) {
        if (input == null || !CONFIRMATION.equals(input.confirmationText()))
            throw new ResetRequestException(400, "请输入“清除聊天与记忆”完成二次确认");
        if (input.requestId() == null || !input.requestId().matches("[a-zA-Z0-9-]{1,80}"))
            throw new ResetRequestException(400, "幂等请求 ID 无效");
        if (!SCOPE.equals(input.scopeVersion())) throw new ResetRequestException(409, "清理范围已变化，请重新预览");
        var state = barrier.state();
        if (Objects.equals(input.requestId(), state.requestId()) && Objects.equals(user.currentUserId(), state.owner())) return current();
        if (barrier.blocked()) throw new ResetRequestException(409, "已有重置任务，请查看当前进度");
        Ticket ticket = tickets.get(input.confirmationToken());
        if (ticket == null || ticket.expiresAt() <= clock.millis()) throw new ResetRequestException(410, "预览确认凭证已失效，请重新预览");
        if (!ticket.owner().equals(user.currentUserId()) || ticket.generation() != state.generation())
            throw new ResetRequestException(409, "用户或状态代次已变化，请重新预览");
        barrier.begin(UUID.randomUUID().toString(), input.requestId(), ticket.owner());
        tickets.clear();
        schedule();
        return current();
    }
    /** 刷新后可发现当前任务，无需依赖前端长期偏好保存任务 ID。 */
    public synchronized Status current() {
        var state = barrier.state();
        if (state.owner() != null && !state.owner().equals(user.currentUserId()))
            throw new ResetRequestException(404, "未找到重置任务");
        return new Status(state.resetId(), state.revision(), state.generation(), state.status(), state.completedSteps(), state.message(),
                List.of("FAILED", "WAITING_RESTART", "WAITING_WRITERS").contains(state.status()), "NOT_INTEGRATED");
    }
    public synchronized Status get(String resetId) {
        if (!Objects.equals(resetId, barrier.state().resetId())) throw new ResetRequestException(404, "未找到重置任务");
        return current();
    }
    public synchronized Status retry(String resetId, Retry input) {
        get(resetId);
        if (input == null || !input.confirmed()) throw new ResetRequestException(400, "请确认继续原重置任务");
        if (barrier.blocked()) schedule();
        return current();
    }
    /** 未完成重置先恢复屏障，再续做；渠道与会话启动恢复分别检查同一屏障。 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recover() {
        if (barrier.blocked() && Objects.equals(user.currentUserId(), barrier.state().owner())) schedule();
    }
    private void schedule() {
        if (closing) return;
        if (working) { pendingRetry = true; return; }
        barrier.progress("QUIESCING", "正在停止旧任务");
        working = true;
        worker.execute(() -> {
            try { execute(); }
            finally {
                synchronized (this) {
                    working = false;
                    if (pendingRetry) {
                        pendingRetry = false;
                        if (barrier.blocked()) schedule();
                    }
                }
            }
        });
    }
    private void execute() {
        String owner = barrier.state().owner();
        try {
            barrier.progress("QUIESCING", "正在停止旧任务");
            runs.cancelAll(owner);
            for (var participant : participants) participant.stop(owner);
            if (!barrier.awaitIdle(Duration.ofSeconds(20))) {
                barrier.progress("WAITING_WRITERS", "旧任务尚未退出，未开始清理。请等待收尾后重试，必要时正常重启后端。"); return;
            }
            if (barrier.runtimeUsed()) {
                barrier.progress("WAITING_RESTART", "SDK 或渠道运行时已使用，请正常重启当前后端；屏障已保存，启动后自动续做。尚未开始删除。"); return;
            }
            if (!participants.stream().map(ResetParticipant::id).toList().containsAll(
                    barrier.state().completedSteps().stream().filter(step -> !List.of("personal-files", "memory-caches").contains(step)).toList()))
                throw new IllegalStateException("重置参与方发生变化");
            files.preflight(owner);
            for (var participant : participants) participant.preflight(owner);
            for (var participant : participants) {
                if (!barrier.state().completedSteps().contains(participant.id())) {
                    participant.clean(owner); barrier.stepDone(participant.id());
                }
            }
            if (!barrier.state().completedSteps().contains("personal-files")) {
                files.clean(owner); barrier.stepDone("personal-files");
            }
            approvals.clearUser(owner);
            barrier.stepDone("memory-caches");
            barrier.complete();
            for (var participant : participants) {
                try { participant.resume(); }
                catch (RuntimeException exception) { log.warn("重置后渠道恢复失败：participant={}", participant.id()); }
            }
        } catch (Exception exception) {
            // 不保存异常链：底层 IO/SQL 异常可能包含路径或正文。
            log.warn("Agent 重置未完成：errorType={}", exception.getClass().getSimpleName());
            String message = exception instanceof ResetScopeException ? exception.getMessage()
                    : "请检查符号链接、文件锁和磁盘权限后重试。";
            try { barrier.progress("FAILED", "清理未完成，保护屏障仍有效。" + message); }
            catch (IllegalStateException persistenceFailure) { log.warn("重置失败状态暂不能持久化，继续保持进程内屏障"); }
        }
    }
    @PreDestroy public synchronized void close() { closing = true; worker.shutdown(); }
    private record Ticket(String owner, long generation, long expiresAt) { }
}
