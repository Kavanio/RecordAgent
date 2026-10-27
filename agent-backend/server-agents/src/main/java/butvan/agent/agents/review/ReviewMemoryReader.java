package butvan.agent.agents.review;

import java.util.List;

/**
 * 长期记忆的只读 seam；实现位于业务层，不向 agents 暴露数据库或持久化 DTO。
 *
 * <p>当前入口仅供已授权的后台复盘，不可被聊天或渠道投递借用权限。
 * 每次读取权威状态，不缓存被纠正或撤销的内容。</p>
 */
public interface ReviewMemoryReader {
    /** 读取当前有效目标、历史行动与候选观察；未授权时拒绝读取。 */
    Snapshot readForAuthorizedReview(String owner);

    /** 四层记忆中的个人目标与召回候选；事实由本轮取证单独提供。 */
    record Snapshot(List<ReviewGenerationRequest.Goal> goals,
                    List<ReviewGenerationRequest.HistoryAction> actions,
                    List<ReviewGenerationRequest.Observation> observations) {
        public Snapshot {
            goals = List.copyOf(goals);
            actions = List.copyOf(actions);
            observations = List.copyOf(observations);
        }
    }
}
