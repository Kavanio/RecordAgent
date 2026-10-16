package butvan.agent.network.study.dto;

import butvan.agent.network.study.model.StudyModels.StudySession;

/**
 * 学习状态 SSE 载荷。每个事件都携带当前权威活动时段，客户端无需再次查询活动状态。
 *
 * @param streamId 当前后端进程的唯一标识，用于识别重启后的版本号重置
 * @param revision 当前后端进程内单调递增的事件版本
 * @param reason 快照或业务变更原因
 * @param changedSessionId 发生变化的学习时段；初始快照时为空
 * @param activeSession 当前进行中的学习时段；没有时为空
 */
public record StudySessionStreamEvent(
        String streamId,
        long revision,
        String reason,
        String changedSessionId,
        StudySession activeSession) {
}
