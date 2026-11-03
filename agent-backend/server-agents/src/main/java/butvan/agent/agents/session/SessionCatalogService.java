package butvan.agent.agents.session;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.project.ProjectRegistry;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionChannel;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.session.dto.SessionStatus;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 应用会话目录册服务。
 *
 * <p>只管理产品需要的会话摘要；不接触 AgentScope 工作区和内部 session 文件。</p>
 */
@Slf4j
@Service
public class SessionCatalogService {

    private final AgentStorageProperties agentStorageProperties;

    private final CurrentUserProvider currentUserProvider;

    private final ObjectMapper objectMapper;

    private final ProjectRegistry projectRegistry;

    /** 生产构造器：项目会话创建必须经项目目录册校验。 */
    @Autowired
    public SessionCatalogService(
            AgentStorageProperties agentStorageProperties,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper,
            ProjectRegistry projectRegistry
    ) {
        this.agentStorageProperties = agentStorageProperties;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
        this.projectRegistry = projectRegistry;
    }

    /** 为隔离测试保留的便利构造器，项目目录册使用相同临时数据根。 */
    public SessionCatalogService(
            AgentStorageProperties agentStorageProperties,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper
    ) {
        this(agentStorageProperties, currentUserProvider, objectMapper,
                new ProjectRegistry(agentStorageProperties, currentUserProvider, objectMapper));
    }

    /**
     * 创建会话
     * @param request
     * @return
     */
    public synchronized SessionSummaryDto create(CreateSessionRequest request) {
        return create(request, SessionChannel.DESKTOP);
    }

    /** 仅供可信飞书 Adapter 创建独立渠道会话。 */
    public synchronized SessionSummaryDto createFeishuSession() {
        return create(new CreateSessionRequest(SessionKind.GENERAL, "飞书对话"), SessionChannel.FEISHU);
    }

    private SessionSummaryDto create(CreateSessionRequest request, SessionChannel channel) {
        SessionKind kind = request != null && request.kind() != null ? request.kind() : SessionKind.GENERAL;
        String projectId = request == null ? null : request.projectId();
        if (kind == SessionKind.PROJECT) {
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("项目会话必须绑定项目 ID");
            }
            projectRegistry.resolve(projectId);
        } else if (projectId != null && !projectId.isBlank()) {
            throw new IllegalArgumentException("普通会话不能绑定项目");
        }

        Instant now = Instant.now();
        SessionSummaryDto created = new SessionSummaryDto(
                UUID.randomUUID().toString(),
                kind,
                projectId,
                normalizeTitle(request == null ? null : request.title(), "新对话"),
                "",
                now,
                now,
                SessionStatus.ACTIVE
        );

        List<CatalogRecord> records = readRecords();
        records.add(CatalogRecord.from(currentUserProvider.currentUserId(), created, channel));
        writeRecords(records);

        return created;
    }

    /** 返回当前用户所有未删除会话，按最近更新时间倒序。 */
    public synchronized List<SessionSummaryDto> listActive() {
        String ownerId = currentUserProvider.currentUserId();
        return readRecords().stream()
                .filter(record -> ownerId.equals(record.ownerId()))
                .filter(record -> record.status() == SessionStatus.ACTIVE)
                .map(CatalogRecord::toDto)
                .sorted(Comparator.comparing(SessionSummaryDto::updatedAt).reversed())
                .toList();
    }

    /** 按可信渠道返回会话；默认旧记录属于桌面端。 */
    public synchronized List<SessionSummaryDto> listActive(SessionChannel channel) {
        String ownerId = currentUserProvider.currentUserId();
        return readRecords().stream()
                .filter(record -> ownerId.equals(record.ownerId()) && record.status() == SessionStatus.ACTIVE)
                .filter(record -> record.effectiveChannel() == channel)
                .map(CatalogRecord::toDto)
                .sorted(Comparator.comparing(SessionSummaryDto::updatedAt).reversed())
                .toList();
    }

    /** 读取持久化渠道；标题、权限模式及调用方参数均不能改变渠道。 */
    public synchronized SessionChannel getChannel(String sessionId) {
        return requireRecord(sessionId, false).effectiveChannel();
    }

    /** 桌面端入口拒绝访问飞书会话，包括旧客户端缓存的会话 ID。 */
    public synchronized SessionSummaryDto requireDesktopActive(String sessionId) {
        requireDesktopChannel(sessionId);
        return requireActive(sessionId);
    }

    /** 删除重试仍可校验渠道，允许已经处于 DELETING 的桌面会话继续清理。 */
    public synchronized void requireDesktopChannel(String sessionId) {
        if (getChannel(sessionId) != SessionChannel.DESKTOP) {
            throw new IllegalArgumentException("飞书会话仅可在飞书中继续使用");
        }
    }

    /** 只依据可信渠道账本迁移旧会话，不根据标题猜测，不创建或删除历史。 */
    public synchronized void migrateFeishuSessions(java.util.Set<String> sessionIds) {
        List<CatalogRecord> records = readRecords();
        boolean changed = false;
        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (currentUserProvider.currentUserId().equals(record.ownerId())
                    && sessionIds.contains(record.id()) && record.channel() == null
                    && record.kind() == SessionKind.GENERAL) {
                records.set(index, record.withChannel(SessionChannel.FEISHU));
                changed = true;
            }
        }
        if (changed) writeRecords(records);
    }

    /** 校验会话存在、归属正确且可继续使用；聊天、详情、改标题都会复用它。 */
    public synchronized SessionSummaryDto requireActive(String sessionId) {
        String ownerId = currentUserProvider.currentUserId();
        return readRecords().stream()
                .filter(record -> ownerId.equals(record.ownerId()))
                .filter(record -> record.id().equals(sessionId))
                .map(CatalogRecord::toDto)
                .filter(dto -> dto.status() == SessionStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("会话不存在、无权访问或正在删除"));
    }

    /** 只修改标题，其他受保护字段保持不变。 */
    public synchronized SessionSummaryDto updateTitle(String sessionId, String title) {
        List<CatalogRecord> records = readRecords();
        String ownerId = currentUserProvider.currentUserId();

        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (ownerId.equals(record.ownerId()) && record.id().equals(sessionId)
                    && record.status() == SessionStatus.ACTIVE) {
                CatalogRecord updated = record.withTitle(normalizeTitle(title, record.title()), TitleSource.USER);
                records.set(index, updated);
                writeRecords(records);
                return updated.toDto();
            }
        }
        throw new IllegalArgumentException("会话不存在、无权访问或正在删除");
    }

    /** 仅当标题仍由系统占位时写入自动生成标题，避免覆盖用户手动重命名。 */
    public synchronized SessionSummaryDto updateGeneratedTitle(String sessionId, String title) {
        List<CatalogRecord> records = readRecords();
        String ownerId = currentUserProvider.currentUserId();

        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (ownerId.equals(record.ownerId()) && record.id().equals(sessionId)
                    && record.status() == SessionStatus.ACTIVE) {
                if (record.effectiveTitleSource() != TitleSource.DEFAULT) {
                    return record.toDto();
                }
                CatalogRecord updated = record.withTitle(normalizeTitle(title, record.title()), TitleSource.AI);
                records.set(index, updated);
                writeRecords(records);
                return updated.toDto();
            }
        }
        throw new IllegalArgumentException("会话不存在、无权访问或正在删除");
    }

    /** 返回会话当前权限模式，旧目录记录默认使用逐次批准。 */
    public synchronized SessionPermissionMode getPermissionMode(String sessionId) {
        return requireRecord(sessionId).effectivePermissionMode();
    }

    /** 持久化会话权限模式。 */
    public synchronized SessionPermissionMode updatePermissionMode(String sessionId, SessionPermissionMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("权限模式不能为空");
        }
        List<CatalogRecord> records = readRecords();
        String ownerId = currentUserProvider.currentUserId();
        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (ownerId.equals(record.ownerId()) && record.id().equals(sessionId)
                    && record.status() == SessionStatus.ACTIVE) {
                records.set(index, record.withPermissionMode(mode));
                writeRecords(records);
                return mode;
            }
        }
        throw new IllegalArgumentException("会话不存在、无权访问或正在删除");
    }

    /** 完成一轮对话后更新摘要排序和侧边栏预览。 */
    public synchronized void touch(String sessionId, String assistantContent) {
        List<CatalogRecord> records = readRecords();
        String ownerId = currentUserProvider.currentUserId();

        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (ownerId.equals(record.ownerId()) && record.id().equals(sessionId)
                    && record.status() == SessionStatus.ACTIVE) {
                records.set(index, record.withPreview(toPreview(assistantContent)));
                writeRecords(records);
                return;
            }
        }
    }

    /** 将会话置为删除中，阻止后续新的聊天请求进入。 */
    public synchronized void markDeleting(String sessionId) {
        List<CatalogRecord> records = readRecords();
        String ownerId = currentUserProvider.currentUserId();
        boolean updated = false;

        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (ownerId.equals(record.ownerId()) && record.id().equals(sessionId)) {
                records.set(index, record.withStatus(SessionStatus.DELETING));
                updated = true;
                break;
            }
        }
        if (!updated) {
            throw new IllegalArgumentException("会话不存在或无权删除");
        }
        writeRecords(records);
    }

    /** 清理完成后真正移除目录册条目。 */
    public synchronized void remove(String sessionId) {
        String ownerId = currentUserProvider.currentUserId();
        List<CatalogRecord> remaining = readRecords().stream()
                .filter(record -> !(ownerId.equals(record.ownerId()) && record.id().equals(sessionId)))
                .toList();
        writeRecords(remaining);
    }

    /** 项目解除登记时保留历史会话，并将其转为普通会话。 */
    public synchronized void detachProject(String projectId) {
        String ownerId = currentUserProvider.currentUserId();
        List<CatalogRecord> records = readRecords();
        boolean changed = false;
        for (int index = 0; index < records.size(); index++) {
            CatalogRecord record = records.get(index);
            if (ownerId.equals(record.ownerId()) && java.util.Objects.equals(projectId, record.projectId())) {
                records.set(index, record.detachProject());
                changed = true;
            }
        }
        if (changed) writeRecords(records);
    }

    private void writeRecords(List<CatalogRecord> records) {
        Path catalogFile = agentStorageProperties.getSessionCatalogFile();
        Path temporaryFile = catalogFile.resolveSibling(catalogFile.getFileName() + ".tmp");
        try {
            // 先写临时文件，再替换正式文件，避免进程中断留下半个 JSON 文件
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), records);
            Files.move(temporaryFile, catalogFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("会话目录册写入失败", e);
        }
    }

    private List<CatalogRecord> readRecords() {
        Path catalogFile = agentStorageProperties.getSessionCatalogFile();
        if (!Files.exists(catalogFile)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(catalogFile.toFile(), new TypeReference<>() { }));
        } catch (IOException e) {
            // 目录册损坏时不能默默覆盖，线报错，保护用户已有数据
            throw new IllegalArgumentException("会话目录册读取失败，请先备份后修复：" + catalogFile, e);
        }
    }

    private CatalogRecord requireRecord(String sessionId) {
        return requireRecord(sessionId, true);
    }

    private CatalogRecord requireRecord(String sessionId, boolean activeOnly) {
        String ownerId = currentUserProvider.currentUserId();
        return readRecords().stream()
                .filter(record -> ownerId.equals(record.ownerId()))
                .filter(record -> record.id().equals(sessionId))
                .filter(record -> !activeOnly || record.status() == SessionStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("会话不存在、无权访问或正在删除"));
    }

    private String normalizeTitle(String rawTitle, String defaultTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return defaultTitle;
        }
        // 标题是产品文案，不允许无限长或换行。
        String oneLine = rawTitle.strip().replaceAll("[\\r\\n]+", " ");
        return oneLine.substring(0, Math.min(80, oneLine.length()));
    }

    private String toPreview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String oneLine = content.strip().replaceAll("[\\r\\n]+", " ");
        return oneLine.substring(0, Math.min(100, oneLine.length()));
    }

    /**
     * 写入磁盘的内部结构
     * @param id
     * @param ownerId
     * @param kind
     * @param title
     * @param lastMessagePreview
     * @param createAt
     * @param updateAt
     * @param status
     * @param titleSource 标题来源；旧数据缺失时根据标题内容兼容推断
     * @param permissionMode 会话权限模式；旧数据缺失时回退 ASK
     * @param channel 创建渠道；旧数据由可信账本补充，缺失时按桌面会话处理
     */
    private record CatalogRecord(
            String id,
            String ownerId,
            SessionKind kind,
            String projectId,
            String title,
            String lastMessagePreview,
            Instant createAt,
            Instant updateAt,
            SessionStatus status,
            TitleSource titleSource,
            SessionPermissionMode permissionMode,
            SessionChannel channel
    ) {

        static CatalogRecord from(String ownerId, SessionSummaryDto dto, SessionChannel channel) {
            return new CatalogRecord(dto.id(), ownerId, dto.kind(), dto.projectId(), dto.title(), dto.lastMessagePreview(), dto.createdAt(), dto.updatedAt(), dto.status(), TitleSource.DEFAULT, SessionPermissionMode.ASK, channel);
        }

        CatalogRecord withTitle(String newTitle, TitleSource newSource) {
            return new CatalogRecord(id, ownerId, kind, projectId, newTitle, lastMessagePreview, createAt, updateAt, status, newSource, permissionMode, channel);
        }

        CatalogRecord withPreview(String preview) {
            return new CatalogRecord(id, ownerId, kind, projectId, title, preview, createAt, updateAt, status, titleSource, permissionMode, channel);
        }

        CatalogRecord withStatus(SessionStatus newStatus) {
            return new CatalogRecord(id, ownerId, kind, projectId, title, lastMessagePreview, createAt, updateAt, newStatus, titleSource, permissionMode, channel);
        }

        CatalogRecord withPermissionMode(SessionPermissionMode newMode) {
            return new CatalogRecord(id, ownerId, kind, projectId, title, lastMessagePreview, createAt, updateAt, status, titleSource, newMode, channel);
        }

        CatalogRecord detachProject() {
            return new CatalogRecord(id, ownerId, SessionKind.GENERAL, null, title, lastMessagePreview, createAt, updateAt, status, titleSource, permissionMode, channel);
        }

        CatalogRecord withChannel(SessionChannel newChannel) {
            return new CatalogRecord(id, ownerId, kind, projectId, title, lastMessagePreview, createAt, updateAt, status, titleSource, permissionMode, newChannel);
        }

        SessionChannel effectiveChannel() {
            return channel == null ? SessionChannel.DESKTOP : channel;
        }

        TitleSource effectiveTitleSource() {
            if (titleSource != null) return titleSource;
            return title == null || title.isBlank() || "新对话".equals(title) ? TitleSource.DEFAULT : TitleSource.USER;
        }

        SessionPermissionMode effectivePermissionMode() {
            return permissionMode == null ? SessionPermissionMode.ASK : permissionMode;
        }

        SessionSummaryDto toDto() {
            return new SessionSummaryDto(id, kind, projectId, title, lastMessagePreview, createAt, updateAt, status);
        }
    }

    private enum TitleSource {
        DEFAULT,
        AI,
        USER
    }
}
