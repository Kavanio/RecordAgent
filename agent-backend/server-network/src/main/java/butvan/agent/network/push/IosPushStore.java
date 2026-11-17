package butvan.agent.network.push;

import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.push.IosPushModels.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 将 iOS 推送配置、设备登记与最近通知列表原子保存至 `~/.butvan-agent/ios-push.json`（0600 权限）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IosPushStore {
    private static final int MAX_NOTICES = 50;
    private static final int MAX_DEVICES = 16;
    private static final long MAX_FILE_BYTES = 2_000_000L;

    private final AgentStorageProperties storageProperties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public synchronized PushState load() {
        Path file = storageProperties.getIosPushFile();
        if (file == null || !Files.exists(file) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            return PushState.defaults();
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                log.warn("iOS 推送状态文件超过上限，使用默认状态");
                return PushState.defaults();
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return PushState.defaults();
            }
            PushState parsed = objectMapper.readValue(raw, PushState.class);
            return parsed == null ? PushState.defaults() : parsed;
        } catch (IOException e) {
            log.warn("读取 iOS 推送状态文件失败：{}", e.getClass().getSimpleName());
            return PushState.defaults();
        }
    }

    public synchronized PushState save(PushState state) {
        Path target = storageProperties.getIosPushFile();
        try {
            Path parent = target.getParent();
            if (parent != null) {
                if (Files.isSymbolicLink(parent)) {
                    throw new IllegalStateException("iOS 推送配置目录不能是符号链接");
                }
                Files.createDirectories(parent);
            }
            if (Files.isSymbolicLink(target)) {
                throw new IllegalStateException("iOS 推送配置文件不能是符号链接");
            }
            Path temp = Files.createTempFile(parent, ".ios-push-", ".tmp");
            try {
                // 使用显式反序列化友好的 writer（注意：ApnsConfig.privateKeyPem 在 record 上标注了 WRITE_ONLY，
                // 文件持久化需要完整保存私钥，因此使用单独的持久化视图写入）。
                String json = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(PersistedPushState.from(state));
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                restrictOwnerOnly(temp);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictOwnerOnly(target);
            } finally {
                Files.deleteIfExists(temp);
            }
            return state;
        } catch (IOException e) {
            throw new IllegalStateException("保存 iOS 推送状态失败", e);
        }
    }

    public synchronized PushState upsertDevice(DeviceRegistration registration) {
        PushState current = load();
        List<DeviceRegistration> next = new ArrayList<>();
        next.add(registration);
        for (DeviceRegistration existing : current.devices()) {
            if (existing.ownerId().equals(registration.ownerId()) && existing.deviceId().equals(registration.deviceId())) {
                continue;
            }
            // 同一个非空 APNs Token 迁移到新 deviceId 时，清理旧绑定避免重复弹窗。
            if (registration.hasApnsToken() && existing.hasApnsToken()
                    && existing.deviceToken().equals(registration.deviceToken())) {
                continue;
            }
            if (next.size() < MAX_DEVICES) {
                next.add(existing);
            }
        }
        return save(new PushState(current.apnsConfig(), next, current.notices()));
    }

    public synchronized PushState removeDevice(String ownerId, String deviceId) {
        PushState current = load();
        List<DeviceRegistration> next = current.devices().stream()
                .filter(d -> !(d.ownerId().equals(ownerId) && d.deviceId().equals(deviceId)))
                .toList();
        return save(new PushState(current.apnsConfig(), next, current.notices()));
    }

    public synchronized PushState removeInvalidToken(String deviceToken) {
        String normalized = DeviceRegistration.normalizeToken(deviceToken);
        if (normalized.isBlank()) return load();
        PushState current = load();
        List<DeviceRegistration> next = current.devices().stream()
                .map(d -> normalized.equals(d.deviceToken())
                        ? new DeviceRegistration(d.ownerId(), d.deviceId(), "", d.environment(), d.appVersion(),
                                d.companionPushEnabled(), d.signalPushEnabled(), d.automationPushEnabled(), d.updatedAt())
                        : d)
                .toList();
        return save(new PushState(current.apnsConfig(), next, current.notices()));
    }

    public synchronized PushState appendNotice(IosNotice notice) {
        PushState current = load();
        List<IosNotice> next = new ArrayList<>(current.notices().size() + 1);
        next.add(notice);
        for (IosNotice existing : current.notices()) {
            if (!existing.id().equals(notice.id()) && next.size() < MAX_NOTICES) {
                next.add(existing);
            }
        }
        return save(new PushState(current.apnsConfig(), current.devices(), next));
    }

    public synchronized PushState updateNoticeApnsStatus(String ownerId, String noticeId, String apnsStatus) {
        PushState current = load();
        List<IosNotice> next = current.notices().stream()
                .map(n -> (n.ownerId().equals(ownerId) && n.id().equals(noticeId))
                        ? n.withApnsStatus(apnsStatus)
                        : n)
                .toList();
        return save(new PushState(current.apnsConfig(), current.devices(), next));
    }

    public synchronized PushState acknowledgeNotice(String ownerId, String noticeId) {
        PushState current = load();
        List<IosNotice> next = current.notices().stream()
                .map(n -> (n.ownerId().equals(ownerId) && n.id().equals(noticeId))
                        ? n.withAcknowledged(true)
                        : n)
                .toList();
        return save(new PushState(current.apnsConfig(), current.devices(), next));
    }

    public synchronized PushState clearNoticesForOwner(String ownerId) {
        PushState current = load();
        List<IosNotice> next = current.notices().stream()
                .filter(n -> !n.ownerId().equals(ownerId))
                .toList();
        return save(new PushState(current.apnsConfig(), current.devices(), next));
    }

    private void restrictOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统（如 Windows）忽略 POSIX 权限设置。
        }
    }

    /** 专用于本地文件序列化，避免 ApnsConfig 上的 WRITE_ONLY 丢弃私钥。 */
    private record PersistedApnsConfig(
            boolean enabled,
            String teamId,
            String keyId,
            String bundleId,
            String privateKeyPem,
            boolean sandbox
    ) {}

    private record PersistedPushState(
            PersistedApnsConfig apnsConfig,
            List<DeviceRegistration> devices,
            List<IosNotice> notices
    ) {
        private static PersistedPushState from(PushState state) {
            ApnsConfig cfg = state.apnsConfig();
            return new PersistedPushState(
                    new PersistedApnsConfig(cfg.enabled(), cfg.teamId(), cfg.keyId(), cfg.bundleId(),
                            cfg.privateKeyPem(), cfg.sandbox()),
                    state.devices(),
                    state.notices()
            );
        }
    }
}
