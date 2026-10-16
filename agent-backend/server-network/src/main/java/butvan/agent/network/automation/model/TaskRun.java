package butvan.agent.network.automation.model;

/** 一次执行的内容与各渠道状态，确认状态不由系统通知是否被关闭推断。 */
public record TaskRun(String id, String taskId, String ownerId, String title, String content,
        long plannedAt, long createdAt, String source, String status, String desktopStatus,
        String emailStatus, String recipient, String confirmation, boolean sound, String error, String emailHtml, long emailQueuedAt) {
    /** 旧记录及非邮件执行兼容入口。 */
    public TaskRun(String id, String taskId, String ownerId, String title, String content,
            long plannedAt, long createdAt, String source, String status, String desktopStatus,
            String emailStatus, String recipient, String confirmation, boolean sound, String error) {
        this(id, taskId, ownerId, title, content, plannedAt, createdAt, source, status, desktopStatus,
                emailStatus, recipient, confirmation, sound, error, "", createdAt);
    }
}
