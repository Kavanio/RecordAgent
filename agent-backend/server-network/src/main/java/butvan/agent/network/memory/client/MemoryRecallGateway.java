package butvan.agent.network.memory.client;

import java.util.List;
import java.util.function.BooleanSupplier;

/** 远端召回内部 seam；当前仅关闭／假 Adapter，生产 HTTP 仍待合约验证。 */
public interface MemoryRecallGateway {
    String recipientFingerprint();
    /** 实现须用剩余期限约束底层 I/O，并在取消时真正终止请求。 */
    List<Candidate> recall(Query query, Control control);

    record Query(String spaceId, String query, int maxTokens, int limit) {
        @Override public String toString() { return "MemoryRecallQuery{query=redacted}"; }
    }
    /** completeProvenance 表示这是全部来源，不能把未知混合来源的派生事实当成可过滤。 */
    record Candidate(String text, List<String> documentIds, boolean completeProvenance) { }
    record Control(long deadlineNanos, BooleanSupplier cancelled) {
        public boolean isCancelled() {
            return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos;
        }
        public long remainingMillis() { return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000); }
    }
    static MemoryRecallGateway disabled() {
        return new MemoryRecallGateway() {
            public String recipientFingerprint() { return ""; }
            public List<Candidate> recall(Query query, Control control) { return List.of(); }
        };
    }
}
