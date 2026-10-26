package butvan.agent.feishu.config;

import java.util.List;

/** 飞书渠道配置；首版限定一个已明确配置的本人 open_id，只开放私聊。 */
public record FeishuConfigData(boolean enabled, String appId, String appSecret,
                               List<String> allowedSenderIds, int heartbeatSeconds,
                               int executionTimeoutSeconds, int queueTimeoutSeconds,
                               int maxQueuedPerSession, int maxConcurrentRuns) {

    public FeishuConfigData {
        allowedSenderIds = allowedSenderIds == null ? List.of()
                : allowedSenderIds.stream().filter(id -> id != null && !id.isBlank()).map(String::strip).distinct().toList();
        if (allowedSenderIds.size() > 1 || heartbeatSeconds < 30 || heartbeatSeconds > 600
                || executionTimeoutSeconds < 60 || executionTimeoutSeconds > 3600
                || queueTimeoutSeconds < 60 || queueTimeoutSeconds > 3600
                || maxQueuedPerSession < 1 || maxQueuedPerSession > 20
                || maxConcurrentRuns < 1 || maxConcurrentRuns > 4) {
            throw new IllegalArgumentException("飞书配置超出允许范围");
        }
    }

    public static FeishuConfigData disabled() {
        return new FeishuConfigData(false, "", "", List.of(), 60, 600, 600, 5, 2);
    }

    /** 旧配置缺少本人白名单时安全禁用，不能把空名单解释为允许所有人。 */
    public boolean isReady() {
        return enabled && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank() && allowedSenderIds.size() == 1;
    }

    /** 不允许 record 默认 toString 泄露凭据。 */
    @Override
    public String toString() {
        return "FeishuConfigData[enabled=" + enabled + ", credentials=<REDACTED>]";
    }
}
