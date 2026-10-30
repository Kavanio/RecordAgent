package butvan.agent.agents.review;

import butvan.agent.agents.memory.MemoryRecallPort;
import java.util.Optional;

/**
 * 自动任务报告邮件与飞书提醒向聊天提供的只读上下文端口。
 *
 * <p>仅在用户提问涉及日报、周报、月报、邮件或提醒时，返回最近已发送报告的非敏感摘要片段；
 * 统一纳入 {@code ConversationContextAssembler} 的硬预算与实时有效性复核。</p>
 */
public interface TaskReportContextPort {
    Optional<MemoryRecallPort.Fragment> recallRecentReport(String owner, String query);
}
