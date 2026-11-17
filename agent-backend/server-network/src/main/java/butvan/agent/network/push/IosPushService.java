package butvan.agent.network.push;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.network.push.IosPushDtos.*;
import butvan.agent.network.push.IosPushModels.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一协调 iOS 设备登记、前台 SSE 实时提醒、APNs 远程弹窗与三类主动事件投递。
 */
@Slf4j
@Service
public class IosPushService {
    private final IosPushStore store;
    private final ApnsClient apnsClient;
    private final IosPushStreamService streamService;
    private final Clock clock;
    private final ResetBarrier resetBarrier;
    private final ApnsConfig envFallbackApns;
    private final ExecutorService apnsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ios-push-apns");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public IosPushService(
            IosPushStore store,
            ApnsClient apnsClient,
            IosPushStreamService streamService,
            Clock automationClock,
            @Autowired(required = false) ResetBarrier resetBarrier,
            @Value("${butvan.push.apns.enabled:false}") boolean envEnabled,
            @Value("${butvan.push.apns.team-id:}") String envTeamId,
            @Value("${butvan.push.apns.key-id:}") String envKeyId,
            @Value("${butvan.push.apns.bundle-id:com.butvan.recordagent}") String envBundleId,
            @Value("${butvan.push.apns.private-key:}") String envPrivateKey,
            @Value("${butvan.push.apns.sandbox:true}") boolean envSandbox
    ) {
        this.store = store;
        this.apnsClient = apnsClient;
        this.streamService = streamService;
        this.clock = automationClock == null ? Clock.systemUTC() : automationClock;
        this.resetBarrier = resetBarrier;
        this.envFallbackApns = new ApnsConfig(envEnabled, envTeamId, envKeyId, envBundleId, envPrivateKey, envSandbox);
    }

    public SseEmitter subscribe(String ownerId) {
        return streamService.subscribe(ownerId, status(ownerId));
    }

    public IosPushStatusResponse status(String ownerId) {
        PushState state = store.load();
        ApnsConfig apns = effectiveApnsConfig(state);
        List<DeviceRegistration> ownerDevices = state.devices().stream()
                .filter(d -> d.ownerId().equals(ownerId))
                .toList();
        List<IosNoticeResponse> pending = state.notices().stream()
                .filter(n -> n.ownerId().equals(ownerId) && !n.acknowledged())
                .map(IosNoticeResponse::from)
                .toList();
        int tokenCount = (int) ownerDevices.stream().filter(DeviceRegistration::hasApnsToken).count();
        return new IosPushStatusResponse(
                apns.configured(),
                apns.enabled(),
                apns.sandbox(),
                apns.teamId(),
                apns.keyId(),
                apns.bundleId(),
                ownerDevices.size(),
                tokenCount,
                pending.size(),
                ownerDevices.stream().map(DeviceSummary::from).toList(),
                pending
        );
    }

    public IosPushStatusResponse registerDevice(String ownerId, RegisterDeviceRequest req) {
        if (req == null || req.deviceId() == null || req.deviceId().isBlank()) {
            throw new IllegalArgumentException("设备标识不能为空");
        }
        String deviceId = req.deviceId().strip();
        if (deviceId.length() > 128) {
            throw new IllegalArgumentException("设备标识长度超限");
        }
        PushState current = store.load();
        DeviceRegistration existing = current.devices().stream()
                .filter(d -> d.ownerId().equals(ownerId) && d.deviceId().equals(deviceId))
                .findFirst()
                .orElse(null);

        String resolvedToken = req.deviceToken() != null
                ? DeviceRegistration.normalizeToken(req.deviceToken())
                : (existing == null ? "" : existing.deviceToken());
        ApnsEnvironment env = req.environment() != null
                ? ApnsEnvironment.parse(req.environment())
                : (existing == null ? ApnsEnvironment.SANDBOX : existing.environment());
        String appVersion = req.appVersion() != null
                ? req.appVersion().strip()
                : (existing == null ? "" : existing.appVersion());
        boolean companionEnabled = req.companionPushEnabled() != null
                ? req.companionPushEnabled()
                : (existing == null || existing.companionPushEnabled());
        boolean signalEnabled = req.signalPushEnabled() != null
                ? req.signalPushEnabled()
                : (existing == null || existing.signalPushEnabled());
        boolean automationEnabled = req.automationPushEnabled() != null
                ? req.automationPushEnabled()
                : (existing == null || existing.automationPushEnabled());

        DeviceRegistration registration = new DeviceRegistration(
                ownerId, deviceId, resolvedToken, env, appVersion,
                companionEnabled, signalEnabled, automationEnabled, clock.instant()
        );
        store.upsertDevice(registration);
        IosPushStatusResponse snapshot = status(ownerId);
        streamService.publish(ownerId, snapshot);
        return snapshot;
    }

    public IosPushStatusResponse removeDevice(String ownerId, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            store.removeDevice(ownerId, deviceId.strip());
        }
        IosPushStatusResponse snapshot = status(ownerId);
        streamService.publish(ownerId, snapshot);
        return snapshot;
    }

    public IosPushStatusResponse saveApnsConfig(String ownerId, SaveApnsConfigRequest req) {
        if (req == null) throw new IllegalArgumentException("APNs 配置不能为空");
        PushState current = store.load();
        ApnsConfig prev = current.apnsConfig();
        String nextPem = (req.privateKeyPem() == null || req.privateKeyPem().isBlank())
                ? prev.privateKeyPem()
                : req.privateKeyPem().strip();
        ApnsConfig next = new ApnsConfig(
                req.enabled() != null ? req.enabled() : prev.enabled(),
                req.teamId() != null ? req.teamId() : prev.teamId(),
                req.keyId() != null ? req.keyId() : prev.keyId(),
                req.bundleId() != null ? req.bundleId() : prev.bundleId(),
                nextPem,
                req.sandbox() != null ? req.sandbox() : prev.sandbox()
        );
        if (next.enabled() && !next.configured()) {
            throw new IllegalArgumentException("启用 APNs 推送前请先填全 Team ID、Key ID、Bundle ID 与 .p8 私钥");
        }
        store.save(new PushState(next, current.devices(), current.notices()));
        IosPushStatusResponse snapshot = status(ownerId);
        streamService.publish(ownerId, snapshot);
        return snapshot;
    }

    public List<IosNoticeResponse> notices(String ownerId, boolean includeAcknowledged) {
        return store.load().notices().stream()
                .filter(n -> n.ownerId().equals(ownerId) && (includeAcknowledged || !n.acknowledged()))
                .map(IosNoticeResponse::from)
                .toList();
    }

    public IosPushStatusResponse acknowledgeNotice(String ownerId, String noticeId) {
        if (noticeId != null && !noticeId.isBlank()) {
            store.acknowledgeNotice(ownerId, noticeId.strip());
        }
        IosPushStatusResponse snapshot = status(ownerId);
        streamService.publish(ownerId, snapshot);
        return snapshot;
    }

    /**
     * 统一向 iOS 端发布主动通知：
     * 1) 始终写入待送达通知流并通过 SSE 实时触达前台设备；
     * 2) 若服务端 APNs 已配置且设备已登记 Token，则异步通过 HTTP/2 APNs 发出离屏系统弹窗。
     */
    public IosNotice publishNotice(
            String ownerId,
            PushCategory category,
            String kind,
            String title,
            String body,
            String sessionId,
            String routeTarget
    ) {
        if (ownerId == null || ownerId.isBlank() || body == null || body.isBlank()) return null;
        if (resetBarrier != null && resetBarrier.blocked()) return null;
        long generation = resetBarrier == null ? -1 : resetBarrier.state().generation();

        PushState state = store.load();
        List<DeviceRegistration> ownerDevices = state.devices().stream()
                .filter(d -> d.ownerId().equals(ownerId))
                .toList();
        // 若用户至少登记了一台设备，且所有设备均关闭了该类通知，则直接跳过，不打扰用户。
        if (!ownerDevices.isEmpty() && ownerDevices.stream().noneMatch(d -> d.allows(category))) {
            return null;
        }

        ApnsConfig apns = effectiveApnsConfig(state);
        List<DeviceRegistration> apnsTargets = ownerDevices.stream()
                .filter(d -> d.allows(category) && d.hasApnsToken())
                .toList();

        String initialApnsStatus = (!apns.enabled() || !apns.configured())
                ? "NOT_CONFIGURED"
                : (apnsTargets.isEmpty() ? "NO_DEVICE" : "PENDING");

        IosNotice notice = new IosNotice(
                UUID.randomUUID().toString(),
                ownerId,
                category,
                kind,
                title,
                body,
                sessionId,
                routeTarget,
                clock.instant(),
                false,
                initialApnsStatus
        );
        store.appendNotice(notice);
        streamService.publish(ownerId, status(ownerId));

        if ("PENDING".equals(initialApnsStatus)) {
            apnsExecutor.submit(() -> deliverApnsBatch(ownerId, generation, apns, apnsTargets, notice));
        }
        return notice;
    }

    /** 手动发送一条测试推送，同步返回 APNs 实际投递结果摘要。 */
    public TestPushResponse sendTestNotice(String ownerId, TestPushRequest req) {
        PushCategory category = parseCategory(req == null ? null : req.category());
        String title = (req == null || req.title() == null || req.title().isBlank())
                ? "RecordAgent 主动提醒测试"
                : req.title().strip();
        String body = (req == null || req.body() == null || req.body().isBlank())
                ? "这是一条主动推送测试消息，点击可直达对话。"
                : req.body().strip();

        PushState state = store.load();
        ApnsConfig apns = effectiveApnsConfig(state);
        List<DeviceRegistration> targets = state.devices().stream()
                .filter(d -> d.ownerId().equals(ownerId) && d.hasApnsToken())
                .toList();

        IosNotice notice = new IosNotice(
                UUID.randomUUID().toString(),
                ownerId,
                category,
                "PUSH_TEST",
                title,
                body,
                "",
                category == PushCategory.AUTOMATION ? "tasks" : "chat",
                clock.instant(),
                false,
                (!apns.enabled() || !apns.configured()) ? "NOT_CONFIGURED" : (targets.isEmpty() ? "NO_DEVICE" : "PENDING")
        );
        store.appendNotice(notice);
        streamService.publish(ownerId, status(ownerId));

        int sent = 0;
        String summary;
        if (!apns.enabled() || !apns.configured()) {
            summary = "APNs 未在服务端启用或凭据未配置，已推送至前台 SSE 与回前台待拉队列";
        } else if (targets.isEmpty()) {
            summary = "尚无已登记 APNs Token 的设备，已推送至前台 SSE 与回前台待拉队列";
        } else {
            ApnsResultSummary result = executeApnsBatch(ownerId, apns, targets, notice);
            sent = result.sentCount();
            notice = notice.withApnsStatus(result.status());
            summary = result.summary();
        }
        return new TestPushResponse(IosNoticeResponse.from(notice), targets.size(), sent, summary);
    }

    @PreDestroy
    public void shutdown() {
        apnsExecutor.shutdownNow();
    }

    private void deliverApnsBatch(
            String ownerId,
            long expectedGeneration,
            ApnsConfig apns,
            List<DeviceRegistration> targets,
            IosNotice notice
    ) {
        if (resetBarrier != null && (resetBarrier.blocked() || !resetBarrier.isCurrent(expectedGeneration))) {
            store.updateNoticeApnsStatus(ownerId, notice.id(), "SKIPPED");
            return;
        }
        executeApnsBatch(ownerId, apns, targets, notice);
    }

    private ApnsResultSummary executeApnsBatch(
            String ownerId,
            ApnsConfig apns,
            List<DeviceRegistration> targets,
            IosNotice notice
    ) {
        int sent = 0;
        String lastFailure = "";
        for (DeviceRegistration device : targets) {
            ApnsClient.DeliveryOutcome outcome = apnsClient.send(apns, device, notice);
            if (outcome.success()) {
                sent++;
            } else {
                lastFailure = outcome.summary();
                if (outcome.invalidToken()) {
                    store.removeInvalidToken(device.deviceToken());
                }
            }
        }
        String finalStatus = sent > 0 ? "SENT" : "FAILED";
        store.updateNoticeApnsStatus(ownerId, notice.id(), finalStatus);
        streamService.publish(ownerId, status(ownerId));
        String summary = sent > 0
                ? "APNs 已发送至 " + sent + "/" + targets.size() + " 台设备"
                : "APNs 发送未成功：" + (lastFailure.isBlank() ? "UNKNOWN" : lastFailure);
        return new ApnsResultSummary(sent, finalStatus, summary);
    }

    private ApnsConfig effectiveApnsConfig(PushState state) {
        ApnsConfig stored = state.apnsConfig();
        if (stored != null && stored.configured()) {
            return stored;
        }
        return envFallbackApns.configured() ? envFallbackApns : (stored == null ? ApnsConfig.defaults() : stored);
    }

    private static PushCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return PushCategory.COMPANION;
        try {
            return PushCategory.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return PushCategory.COMPANION;
        }
    }

    private record ApnsResultSummary(int sentCount, String status, String summary) {}
}
