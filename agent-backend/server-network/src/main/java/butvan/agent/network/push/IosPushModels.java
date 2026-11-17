package butvan.agent.network.push;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * iOS 主动推送、设备登记与 APNs 凭据的持久化领域模型。
 */
public final class IosPushModels {
    private IosPushModels() {}

    /** 主动推送大类，对应 iOS 端设置页三个独立开关。 */
    public enum PushCategory {
        COMPANION,
        SIGNAL,
        AUTOMATION
    }

    /** APNs 投递环境。 */
    public enum ApnsEnvironment {
        SANDBOX,
        PRODUCTION;

        public static ApnsEnvironment parse(String raw) {
            if (raw != null && "production".equalsIgnoreCase(raw.strip())) {
                return PRODUCTION;
            }
            return SANDBOX;
        }

        public String wireValue() {
            return this == PRODUCTION ? "production" : "sandbox";
        }
    }

    /** APNs 服务端推送配置；私钥只允许写入，永不序列化到接口响应或日志。 */
    public record ApnsConfig(
            boolean enabled,
            String teamId,
            String keyId,
            String bundleId,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String privateKeyPem,
            boolean sandbox
    ) {
        public ApnsConfig {
            teamId = teamId == null ? "" : teamId.strip();
            keyId = keyId == null ? "" : keyId.strip();
            bundleId = (bundleId == null || bundleId.isBlank()) ? "com.butvan.recordagent" : bundleId.strip();
            privateKeyPem = privateKeyPem == null ? "" : privateKeyPem.strip();
        }

        public static ApnsConfig defaults() {
            return new ApnsConfig(false, "", "", "com.butvan.recordagent", "", true);
        }

        public boolean configured() {
            return !teamId.isBlank() && !keyId.isBlank() && !bundleId.isBlank() && !privateKeyPem.isBlank();
        }

        @Override
        public String toString() {
            return "ApnsConfig{enabled=" + enabled + ", teamId='" + teamId + "', keyId='" + keyId
                    + "', bundleId='" + bundleId + "', sandbox=" + sandbox + ", configured=" + configured() + "}";
        }
    }

    /** 单台 iOS 设备登记与推送偏好（即使免费签名暂无 APNs Token，也保留设备 ID 与类别偏好）。 */
    public record DeviceRegistration(
            String ownerId,
            String deviceId,
            String deviceToken,
            ApnsEnvironment environment,
            String appVersion,
            boolean companionPushEnabled,
            boolean signalPushEnabled,
            boolean automationPushEnabled,
            Instant updatedAt
    ) {
        public DeviceRegistration {
            ownerId = ownerId == null ? "" : ownerId.strip();
            deviceId = deviceId == null ? "" : deviceId.strip();
            deviceToken = normalizeToken(deviceToken);
            environment = environment == null ? ApnsEnvironment.SANDBOX : environment;
            appVersion = appVersion == null ? "" : appVersion.strip();
        }

        public boolean allows(PushCategory category) {
            return switch (category) {
                case COMPANION -> companionPushEnabled;
                case SIGNAL -> signalPushEnabled;
                case AUTOMATION -> automationPushEnabled;
            };
        }

        public boolean hasApnsToken() {
            return !deviceToken.isBlank();
        }

        public static String normalizeToken(String raw) {
            if (raw == null) return "";
            String cleaned = raw.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            return cleaned;
        }

        @Override
        public String toString() {
            return "DeviceRegistration{deviceId='" + deviceId + "', hasToken=" + hasApnsToken()
                    + ", environment=" + environment + "}";
        }
    }

    /** 面向 iOS 前台 SSE / 补拉与 APNs 的统一主动通知条目。 */
    public record IosNotice(
            String id,
            String ownerId,
            PushCategory category,
            String kind,
            String title,
            String body,
            String sessionId,
            String routeTarget,
            Instant createdAt,
            boolean acknowledged,
            String apnsStatus
    ) {
        public IosNotice {
            ownerId = ownerId == null ? "" : ownerId.strip();
            category = category == null ? PushCategory.COMPANION : category;
            kind = kind == null ? "" : kind.strip();
            title = (title == null || title.isBlank()) ? "RecordAgent 提醒" : title.strip();
            body = body == null ? "" : body.strip();
            sessionId = sessionId == null ? "" : sessionId.strip();
            routeTarget = (routeTarget == null || routeTarget.isBlank()) ? "chat" : routeTarget.strip();
            apnsStatus = apnsStatus == null ? "NOT_CONFIGURED" : apnsStatus.strip();
        }

        public IosNotice withAcknowledged(boolean ack) {
            return new IosNotice(id, ownerId, category, kind, title, body, sessionId, routeTarget,
                    createdAt, ack, apnsStatus);
        }

        public IosNotice withApnsStatus(String nextStatus) {
            return new IosNotice(id, ownerId, category, kind, title, body, sessionId, routeTarget,
                    createdAt, acknowledged, nextStatus);
        }
    }

    /** `~/.butvan-agent/ios-push.json` 完整持久化文件结构。 */
    public record PushState(
            ApnsConfig apnsConfig,
            List<DeviceRegistration> devices,
            List<IosNotice> notices
    ) {
        public PushState {
            apnsConfig = apnsConfig == null ? ApnsConfig.defaults() : apnsConfig;
            devices = devices == null ? List.of() : List.copyOf(devices);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }

        public static PushState defaults() {
            return new PushState(ApnsConfig.defaults(), List.of(), List.of());
        }
    }
}
