package butvan.agent.agents.memory;

import java.time.Instant;
import java.util.Optional;

/** 经历来源的最小 seam；仅接收可信渠道的无正文描述，不读取或发送聊天。 */
public interface MemorySourceRegistry {
    /** 在原文落盘后登记当时的资格；未授权或暂停时返回空，不补录旧轮次。 */
    Optional<Receipt> capture(Source source);

    /** 发送及结果发布前重新检查原资格；撤权、重置后旧凭据永久失效。 */
    boolean isCurrent(Receipt receipt);

    /** 来源只含原文指针与摘要，调用方必须使用真实用户原文计算哈希。 */
    record Source(String owner, String sessionId, String turnId, String messageId, Instant occurredAt,
                  Channel channel, String contentHash, long generation) { }

    /** 支持的可信渠道；业务命令、资料展开及未知来源不进入此 Interface。 */
    enum Channel { DESKTOP, FEISHU }

    /** 本地资格凭据，不是外发许可；使用时仍须检查当前授权和代次。 */
    record Receipt(String sourceId, String spaceId, long authorizationRevision, long generation) { }

    /** 仅运行 agents 模块时可直接使用；不自动接入任何 Spring 或聊天路径。 */
    static MemorySourceRegistry disabled() {
        return new MemorySourceRegistry() {
            public Optional<Receipt> capture(Source source) { return Optional.empty(); }
            public boolean isCurrent(Receipt receipt) { return false; }
        };
    }
}
