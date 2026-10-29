package butvan.agent.agents.memory;

import java.util.List;
import java.util.function.BooleanSupplier;

/** 有界检索 seam：结果与实时有效性留在本轮内存，不提供供应商对象或外部指令权限。 */
public interface MemoryRecallPort {
    /** 单轮有界检索；未命中与权限／故障状态分别表达，调用方不得用失败冒充空命中。 */
    Result recall(Request request);

    record Request(String owner, String query, int tokenBudget, int topK, MemorySourceRegistry.Channel channel,
                   BooleanSupplier cancelled) { }
    /** 来源说明必须可追溯；有效性检查只能读本地权威状态，不重新请求远端。 */
    record Fragment(String source, String content, BooleanSupplier stillCurrent) {
        public Fragment {
            stillCurrent = stillCurrent == null ? () -> false : stillCurrent;
        }
    }
    enum Status { SUCCESS, EMPTY, NOT_CONFIGURED, NOT_AUTHORIZED, PAUSED, CANCELLED, TIMEOUT, UNAVAILABLE, INVALID_RESULT, BUDGET_EXHAUSTED }
    /** 本地权威阻断原因，不接收远端正文或自由文本。 */
    enum Reason { NONE, DELETION_UNRESOLVED }
    record Result(Status status, List<Fragment> fragments, Reason reason) {
        public Result(Status status, List<Fragment> fragments) { this(status, fragments, Reason.NONE); }
        public Result {
            fragments = fragments == null ? List.of() : List.copyOf(fragments);
            reason = reason == null ? Reason.NONE : reason;
        }
        public static Result empty(Status status) { return new Result(status, List.of()); }
    }
}
