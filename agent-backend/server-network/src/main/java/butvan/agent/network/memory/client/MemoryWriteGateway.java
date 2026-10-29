package butvan.agent.network.memory.client;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/** 增量写入内部 seam；HTTP 协议 Adapter 仅在隔离测试显式使用，生产装配默认关闭。 */
public interface MemoryWriteGateway {
    String recipientFingerprint();
    /** 实现必须复用稳定操作 ID，并在真实请求开始前检查取消信号。 */
    Outcome submit(Write request, BooleanSupplier cancelled);
    /** 结果未知时只查询同一操作，不重新执行提取。 */
    Outcome query(String operationId, MemoryRemoteGateway.Target target, BooleanSupplier cancelled);

    enum Outcome { SUCCEEDED, ACCEPTED, NOT_SUBMITTED_RETRYABLE, FAILED, UNKNOWN }
    /** 正文只在这次调用内存中存在，toString 不暴露正文。 */
    record Write(String operationId, MemoryRemoteGateway.Target target, String content, Instant occurredAt, Channel channel) {
        @Override public String toString() { return "MemoryWrite{content=redacted}"; }
    }
    static MemoryWriteGateway disabled() {
        return new MemoryWriteGateway() {
            public String recipientFingerprint() { return ""; }
            public Outcome submit(Write request, BooleanSupplier cancelled) { return Outcome.UNKNOWN; }
            public Outcome query(String operationId, MemoryRemoteGateway.Target target, BooleanSupplier cancelled) { return Outcome.UNKNOWN; }
        };
    }
}
