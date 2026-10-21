package butvan.agent.network.automation.event;
/** 任务事务成功后刷新快照；纯计时更新不应使执行历史失效。 */
public record TasksChanged(String ownerId, boolean activityOnly) {
    public TasksChanged(String ownerId) { this(ownerId, false); }
}
