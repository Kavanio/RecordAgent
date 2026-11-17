package butvan.agent.network.push;

import butvan.agent.network.push.IosPushModels.*;

import java.time.Instant;
import java.util.List;

/**
 * iOS 主动推送 HTTP / SSE 接口传输 DTO（脱敏，绝不回传 APNs 私钥或完整 Device Token）。
 */
public final class IosPushDtos {
    private IosPushDtos() {}

    /** 设备摘要信息。 */
    public record DeviceSummary(
            String deviceId,
            boolean hasApnsToken,
            String tokenPrefix,
            String environment,
            String appVersion,
            boolean companionPushEnabled,
            boolean signalPushEnabled,
            boolean automationPushEnabled,
            Instant updatedAt
    ) {
        public static DeviceSummary from(DeviceRegistration d) {
            String token = d.deviceToken();
            String prefix = token.length() >= 8 ? token.substring(0, 8) + "…" : (token.isEmpty() ? "" : token);
            return new DeviceSummary(
                    d.deviceId(),
                    d.hasApnsToken(),
                    prefix,
                    d.environment().wireValue(),
                    d.appVersion(),
                    d.companionPushEnabled(),
                    d.signalPushEnabled(),
                    d.automationPushEnabled(),
                    d.updatedAt()
            );
        }
    }

    /** 单条主动通知响应 DTO。 */
    public record IosNoticeResponse(
            String id,
            String category,
            String kind,
            String title,
            String body,
            String sessionId,
            String routeTarget,
            Instant createdAt,
            boolean acknowledged,
            String apnsStatus
    ) {
        public static IosNoticeResponse from(IosNotice n) {
            return new IosNoticeResponse(
                    n.id(),
                    n.category().name(),
                    n.kind(),
                    n.title(),
                    n.body(),
                    n.sessionId(),
                    n.routeTarget(),
                    n.createdAt(),
                    n.acknowledged(),
                    n.apnsStatus()
            );
        }
    }

    /** 当前用户的 iOS 推送总览状态与实时流快照。 */
    public record IosPushStatusResponse(
            boolean apnsConfigured,
            boolean apnsEnabled,
            boolean sandbox,
            String teamId,
            String keyId,
            String bundleId,
            int registeredDeviceCount,
            int apnsTokenDeviceCount,
            int unacknowledgedCount,
            List<DeviceSummary> devices,
            List<IosNoticeResponse> pendingNotices
    ) {}

    /** iOS 端注册或更新设备及推送偏好请求。 */
    public record RegisterDeviceRequest(
            String deviceId,
            String deviceToken,
            String environment,
            String appVersion,
            Boolean companionPushEnabled,
            Boolean signalPushEnabled,
            Boolean automationPushEnabled
    ) {}

    /** 服务端 APNs 凭据配置请求（privateKeyPem 留空保留旧值）。 */
    public record SaveApnsConfigRequest(
            Boolean enabled,
            String teamId,
            String keyId,
            String bundleId,
            String privateKeyPem,
            Boolean sandbox
    ) {}

    /** 测试推送请求。 */
    public record TestPushRequest(
            String title,
            String body,
            String category
    ) {}

    /** 测试推送响应。 */
    public record TestPushResponse(
            IosNoticeResponse notice,
            int attemptedDevices,
            int apnsSentCount,
            String apnsSummary
    ) {}
}
