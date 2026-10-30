package butvan.agent.agents.review;

import butvan.agent.agents.memory.MemoryRecallPort;
import java.util.Optional;

/**
 * 生活复盘向普通聊天提供的只读上下文端口。
 *
 * <p>仅在用户提问涉及复盘、回顾或建议跟进，且当前生活复盘处于有效授权状态时返回摘要片段；
 * 统一纳入 {@code ConversationContextAssembler} 的硬预算与实时有效性复核。</p>
 */
public interface LifeReviewContextPort {
    Optional<MemoryRecallPort.Fragment> recallRecentReview(String owner, String query);
}
