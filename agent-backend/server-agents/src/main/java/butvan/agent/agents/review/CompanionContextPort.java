package butvan.agent.agents.review;

import butvan.agent.agents.memory.MemoryRecallPort;
import java.util.Optional;

/**
 * 主动陪伴向普通聊天提供的只读系统性认知端口。
 *
 * <p>替代对个人画像文件的静默后台覆盖：仅在主动陪伴启用且上下文未失效时返回有界四维认知摘要，
 * 统一受 {@code ConversationContextAssembler} 的硬预算与实时失效屏障约束。</p>
 */
public interface CompanionContextPort {
    Optional<MemoryRecallPort.Fragment> recallCognition(String owner, String query);
}
