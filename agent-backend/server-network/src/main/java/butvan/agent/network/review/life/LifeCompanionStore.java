package butvan.agent.network.review.life;

import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.review.life.LifeCompanionModels.*;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 主动陪伴认知与动态节律状态的本地原子存储。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifeCompanionStore {
    private static final int MAX_DESKTOP_NOTICES = 20;
    private static final long MAX_FILE_BYTES = 1_000_000;

    private final AgentStorageProperties storageProperties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public synchronized CompanionState load(String owner) {
        Path file = stateFile(owner);
        if (!isSafeReadable(owner, file)) {
            return CompanionState.defaults();
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return CompanionState.defaults();
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return CompanionState.defaults();
            CompanionState loaded = objectMapper.readValue(raw, CompanionState.class);
            return loaded == null ? CompanionState.defaults() : loaded;
        } catch (IOException exception) {
            log.warn("读取主动陪伴状态失败，使用默认状态：owner={}", owner);
            return CompanionState.defaults();
        }
    }

    public synchronized CompanionState save(String owner, CompanionState state) {
        Path target = stateFile(owner);
        try {
            Path workspace = storageProperties.userWorkspaceDirectory(owner);
            if (Files.isSymbolicLink(workspace) || Files.isSymbolicLink(target.getParent())) {
                throw new IllegalStateException("主动陪伴目录不能是符号链接");
            }
            Files.createDirectories(target.getParent());
            if (Files.isSymbolicLink(target)) {
                throw new IllegalStateException("主动陪伴状态文件不能是符号链接");
            }
            Path temp = Files.createTempFile(target.getParent(), ".companion-", ".tmp");
            try {
                Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                        StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException("保存主动陪伴状态失败", exception);
        }
    }

    public synchronized DesktopNotice appendDesktopNotice(
            String owner,
            String sessionId,
            String kind,
            String title,
            String content,
            Instant now
    ) {
        CompanionState current = load(owner);
        List<DesktopNotice> next = new ArrayList<>(current.desktopNotices());
        DesktopNotice created = new DesktopNotice(
                UUID.randomUUID().toString(),
                sessionId == null ? "" : sessionId,
                kind == null ? "COMPANION" : kind,
                title == null || title.isBlank() ? "Agent 主动问候" : title.strip(),
                content == null ? "" : content.strip(),
                now,
                false
        );
        next.addFirst(created);
        while (next.size() > MAX_DESKTOP_NOTICES) {
            next.removeLast();
        }
        save(owner, new CompanionState(current.cognition(), current.rhythm(), next));
        return created;
    }

    public synchronized void acknowledgeDesktopNotice(String owner, String noticeId) {
        CompanionState current = load(owner);
        List<DesktopNotice> updated = current.desktopNotices().stream()
                .map(n -> n.id().equals(noticeId)
                        ? new DesktopNotice(n.id(), n.sessionId(), n.kind(), n.title(), n.content(), n.createdAt(), true)
                        : n)
                .toList();
        save(owner, new CompanionState(current.cognition(), current.rhythm(), updated));
    }

    public synchronized void reset(String owner) {
        try {
            Path file = stateFile(owner);
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("清理主动陪伴状态文件失败：owner={}", owner);
        }
    }

    private Path stateFile(String owner) {
        return storageProperties.userWorkspaceDirectory(owner).resolve("companion").resolve("state.json");
    }

    private boolean isSafeReadable(String owner, Path file) {
        Path workspace = storageProperties.userWorkspaceDirectory(owner);
        return !Files.isSymbolicLink(workspace)
                && (file.getParent() == null || !Files.isSymbolicLink(file.getParent()))
                && !Files.isSymbolicLink(file)
                && Files.isRegularFile(file);
    }
}
