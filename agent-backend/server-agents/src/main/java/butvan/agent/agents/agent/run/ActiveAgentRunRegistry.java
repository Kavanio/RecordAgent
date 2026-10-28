package butvan.agent.agents.agent.run;

import butvan.agent.agents.session.AgentStreamSession;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 活动 Agent 运行注册表。
 *
 * <p>用一个稳定 runId 封装运行寻址、同会话互斥和幂等取消，
 * Controller 无需接触生产者线程或 AgentScope 对象。</p>
 */
@Component
public class ActiveAgentRunRegistry {

    private final ConcurrentMap<String, ActiveRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<SessionKey, String> sessionRuns = new ConcurrentHashMap<>();

    /** 注册新运行；同一用户会话同时只允许一轮。 */
    public void register(String userId, String sessionId, AgentStreamSession session) {
        Objects.requireNonNull(session, "session");
        SessionKey key = new SessionKey(userId, sessionId);
        String existingRunId = sessionRuns.putIfAbsent(key, session.runId());
        if (existingRunId != null) {
            throw new IllegalArgumentException("当前会话已有正在运行的回复");
        }
        ActiveRun previous = runs.putIfAbsent(session.runId(), new ActiveRun(userId, sessionId, session));
        if (previous != null) {
            sessionRuns.remove(key, session.runId());
            throw new IllegalArgumentException("runId 已被使用");
        }
    }

    /** 取消精确运行；会话或用户不匹配时按未找到处理。 */
    public CancelResult cancel(String userId, String sessionId, String runId) {
        ActiveRun active = runs.get(runId);
        if (active == null
                || !Objects.equals(active.userId(), userId)
                || !Objects.equals(active.sessionId(), sessionId)) {
            return new CancelResult(runId, false, "NOT_FOUND");
        }
        boolean accepted = active.session().requestCancellation();
        return new CancelResult(runId, accepted, "CANCELLING");
    }

    /** 仅由原运行句柄解注册，避免迟到的 finally 删除后续运行。 */
    public void unregister(String userId, String sessionId, AgentStreamSession session) {
        runs.remove(session.runId(), new ActiveRun(userId, sessionId, session));
        sessionRuns.remove(new SessionKey(userId, sessionId), session.runId());
    }

    /** 发出取消请求；真正停稳由重置租约等待确认。 */
    public void cancelAll(String userId) {
        runs.values().stream().filter(run -> run.userId().equals(userId))
                .forEach(run -> run.session().requestCancellation());
    }

    public record CancelResult(String runId, boolean accepted, String status) {}

    private record ActiveRun(String userId, String sessionId, AgentStreamSession session) {}

    private record SessionKey(String userId, String sessionId) {}
}
