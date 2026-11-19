package butvan.agent.agents.agent.permission;

import butvan.agent.agents.material.ReplyImages;
import butvan.agent.agents.agent.run.AgentRun;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 一次 Permission Ask 产生的一批待审核工具及其恢复状态。 */
public final class PendingApproval {

    private final String approvalId = UUID.randomUUID().toString();
    private final String runId;
    private final AgentRun run;
    private final List<ToolUseBlock> tools;
    private final Map<String, Decision> decisions = new LinkedHashMap<>();
    private State state = State.WAITING;

    /** 兼容测试调用；生产代码必须显式传入客户端 runId。 */
    public PendingApproval(AgentRun run, List<ToolUseBlock> tools) {
        this(run, tools, run != null ? run.turnId() : null);
    }

    public PendingApproval(AgentRun run, List<ToolUseBlock> tools, String runId) {
        if (run == null) throw new IllegalArgumentException("Agent 运行不能为空");
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("待确认工具不能为空");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        this.run = run;
        this.tools = List.copyOf(tools);
        this.runId = runId;
    }

    public String approvalId() { return approvalId; }
    public String runId() { return runId; }
    public AgentRun run() { return run; }

    /** 只接受当前批次中尚未决定的工具，防止重复提交或篡改 callId。 */
    public synchronized void decide(String toolCallId, boolean approved) {
        requireWaiting();
        boolean exists = tools.stream().anyMatch(tool -> tool.getId().equals(toolCallId));
        if (!exists || decisions.containsKey(toolCallId)) {
            throw new IllegalArgumentException("待确认工具不存在或已经处理");
        }
        decisions.put(toolCallId, new Decision(approved));
    }

    /** 原子保存前端对当前剩余工具的整批决定，避免部分提交造成审批状态不完整。 */
    public synchronized void decideBatch(List<PermissionToolDecision> requestedDecisions) {
        requireWaiting();
        if (requestedDecisions == null || requestedDecisions.isEmpty()) {
            throw new IllegalArgumentException("权限决定不能为空");
        }
        Set<String> pendingIds = pendingTools().stream()
                .map(PermissionToolDto::toolCallId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> requestedIds = new HashSet<>();
        for (PermissionToolDecision requested : requestedDecisions) {
            if (requested == null || requested.toolCallId() == null
                    || !pendingIds.contains(requested.toolCallId())
                    || !requestedIds.add(requested.toolCallId())) {
                throw new IllegalArgumentException("待确认工具不存在、已经处理或重复提交");
            }
        }
        if (!requestedIds.equals(pendingIds)) {
            throw new IllegalArgumentException("必须一次提交当前批次的全部待确认工具");
        }
        requestedDecisions.forEach(requested -> decisions.put(
                requested.toolCallId(), new Decision(requested.approved())));
    }

    public synchronized PermissionToolDto nextTool() {
        if (state != State.WAITING) return null;
        for (int i = 0; i < tools.size(); i++) {
            ToolUseBlock tool = tools.get(i);
            if (!decisions.containsKey(tool.getId())) {
                return PermissionToolDto.from(tool, i + 1, tools.size());
            }
        }
        return null;
    }

    /** 返回当前仍待用户决定的整批工具，保持 AgentScope 原始顺序。 */
    public synchronized List<PermissionToolDto> pendingTools() {
        if (state != State.WAITING) return List.of();
        return java.util.stream.IntStream.range(0, tools.size())
                .filter(index -> !decisions.containsKey(tools.get(index).getId()))
                .mapToObj(index -> PermissionToolDto.from(tools.get(index), index + 1, tools.size()))
                .toList();
    }

    public synchronized boolean allDecided() {
        return decisions.size() == tools.size();
    }

    /** 仅供已显式授权的全权限渠道自动批准当前批次。 */
    public synchronized void approveAll() {
        requireWaiting();
        for (ToolUseBlock tool : tools) {
            decisions.putIfAbsent(tool.getId(),
                    new Decision(!ReplyImages.SCREEN_TOOL.equals(tool.getName())));
        }
    }

    public synchronized int toolCount() {
        return tools.size();
    }

    /** 只有全部决定后才构造恢复 AgentScope 的 ConfirmResult 列表。 */
    public synchronized List<ConfirmResult> toConfirmResults() {
        if (!allDecided()) throw new IllegalStateException("仍有工具尚未确认");
        return tools.stream()
                .map(tool -> new ConfirmResult(decisions.get(tool.getId()).approved(), tool))
                .toList();
    }

    public ToolUseBlock findTool(String toolCallId) {
        return tools.stream()
                .filter(tool -> tool.getId().equals(toolCallId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("待确认工具不存在"));
    }

    /** 原子领取恢复权，避免同一批工具被并发执行。 */
    public synchronized void claimForResume(String requestedRunId) {
        if (!runId.equals(requestedRunId)) {
            throw new IllegalArgumentException("runId 与原始运行不匹配");
        }
        if (!allDecided()) throw new IllegalArgumentException("请先逐条完成所有工具确认");
        requireWaiting();
        state = State.RESUMING;
    }

    /** 恢复线程尚未启动时释放领取，允许客户端安全重试。 */
    public synchronized void releaseResumeClaim() {
        if (state == State.RESUMING) state = State.WAITING;
    }

    public synchronized boolean isWaiting() {
        return state == State.WAITING;
    }

    private void requireWaiting() {
        if (state != State.WAITING) throw new IllegalArgumentException("确认请求正在恢复或已经处理");
    }

    private enum State { WAITING, RESUMING }

    private record Decision(boolean approved) {}
}
