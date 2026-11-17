package butvan.agent.network.material.service;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.material.ChatMaterialRecorder;
import butvan.agent.network.file.model.FileAsset;
import butvan.agent.network.file.service.FileAssetService;
import butvan.agent.network.material.model.ChatMaterial;
import butvan.agent.network.material.repository.ChatMaterialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对话档案（素材）领域服务：负责将对话图片/文件按多级分类归档落盘，
 * 生成唯一文件 ID 并维护检索元数据。
 */
@Service
@RequiredArgsConstructor
public class ChatMaterialService implements ChatMaterialRecorder {

    private static final int MAX_PAGE_LIMIT = 100;

    private final FileAssetService fileAssetService;
    private final ChatMaterialRepository repository;

    @Override
    @Transactional
    public List<String> recordUserImages(SaveImagesCommand command) {
        if (command == null || command.images().isEmpty()) {
            return List.of();
        }
        String ownerId = requireIdentifier(command.ownerId(), "用户 ID");
        String sessionId = requireIdentifier(command.sessionId(), "会话 ID");
        String turnId = requireIdentifier(command.turnId(), "轮次 ID");
        String channel = normalizeEnum(command.channel(), "DESKTOP");
        String sessionKind = normalizeEnum(command.sessionKind(), "GENERAL");
        String sessionTitle = normalizeTitle(command.sessionTitle());
        String projectId = blankToNull(command.projectId());

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String dateText = today.toString();
        String monthText = dateText.substring(0, 7);

        List<String> fileIds = new ArrayList<>(command.images().size());
        for (int index = 0; index < command.images().size(); index++) {
            AgentImageAttachment image = command.images().get(index);
            String subType = subTypeForMediaType(image.mediaType());
            String extension = extensionForSubType(subType);
            String relativeDirectory = String.join("/",
                    channel.toLowerCase(Locale.ROOT),
                    sessionKind.toLowerCase(Locale.ROOT),
                    sessionId,
                    "images",
                    subType,
                    monthText,
                    dateText
            );
            String originalName = "chat-image-" + dateText + "-" + (index + 1) + "." + extension;

            FileAsset asset = fileAssetService.createAndBindCategorized(
                    ownerId,
                    FileAssetService.DOMAIN_CHAT_MATERIAL,
                    turnId,
                    FileAssetService.ROLE_CHAT_ATTACHMENT,
                    relativeDirectory,
                    extension,
                    originalName,
                    image.mediaType(),
                    new ByteArrayInputStream(image.data()),
                    AgentImageAttachment.MAX_BYTES
            );

            String archivePath = relativeDirectory + "/" + asset.id() + "." + extension;
            Instant now = Instant.now();
            repository.insert(new ChatMaterial(
                    asset.id(),
                    ownerId,
                    asset.id(),
                    channel,
                    sessionKind,
                    sessionId,
                    projectId,
                    sessionTitle,
                    turnId,
                    "IMAGE",
                    subType.toUpperCase(Locale.ROOT),
                    asset.originalName(),
                    asset.mediaType(),
                    asset.sizeBytes(),
                    asset.sha256(),
                    archivePath,
                    dateText,
                    now,
                    null
            ));
            fileIds.add(asset.id());
        }
        return List.copyOf(fileIds);
    }

    public ChatMaterialRepository.PageResult list(
            String ownerId,
            String category,
            String subType,
            String channel,
            String sessionKind,
            String sessionId,
            String fromDate,
            String toDate,
            String query,
            int limit,
            int offset
    ) {
        int safeLimit = Math.max(1, Math.min(MAX_PAGE_LIMIT, limit));
        int safeOffset = Math.max(0, offset);
        validateDateOptional(fromDate, "开始日期");
        validateDateOptional(toDate, "结束日期");
        if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()
                && fromDate.compareTo(toDate) > 0) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return repository.search(
                ownerId,
                new ChatMaterialRepository.Filter(
                        normalizeUpperOptional(category),
                        normalizeUpperOptional(subType),
                        normalizeUpperOptional(channel),
                        normalizeUpperOptional(sessionKind),
                        blankToNull(sessionId),
                        blankToNull(fromDate),
                        blankToNull(toDate),
                        query
                ),
                safeLimit,
                safeOffset
        );
    }

    public ChatMaterialRepository.SummaryResult summary(String ownerId) {
        return repository.summarize(ownerId);
    }

    public ChatMaterial get(String ownerId, String fileId) {
        requireFileId(fileId);
        return repository.findByFileId(ownerId, fileId)
                .orElseThrow(() -> new IllegalArgumentException("档案素材不存在"));
    }

    public Resource openContent(String ownerId, String fileId) {
        ChatMaterial material = get(ownerId, fileId);
        return new InputStreamResource(fileAssetService.open(
                ownerId,
                material.fileId(),
                FileAssetService.DOMAIN_CHAT_MATERIAL,
                material.turnId(),
                FileAssetService.ROLE_CHAT_ATTACHMENT
        ));
    }

    @Transactional
    public void delete(String ownerId, String fileId) {
        ChatMaterial material = get(ownerId, fileId);
        repository.deleteByFileId(ownerId, material.fileId());
        fileAssetService.unbind(
                ownerId,
                material.fileId(),
                FileAssetService.DOMAIN_CHAT_MATERIAL,
                material.turnId(),
                FileAssetService.ROLE_CHAT_ATTACHMENT
        );
    }

    private String subTypeForMediaType(String mediaType) {
        if (mediaType == null) return "bin";
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpeg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }

    private String extensionForSubType(String subType) {
        return "jpeg".equals(subType) ? "jpg" : subType;
    }

    private String normalizeEnum(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!candidate.matches("[A-Z0-9_]{1,32}")) {
            throw new IllegalArgumentException("分类标识格式非法");
        }
        return candidate;
    }

    private String normalizeUpperOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) return "新对话";
        String trimmed = title.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateDateOptional(String date, String label) {
        if (date == null || date.isBlank()) return;
        try {
            LocalDate.parse(date.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + "格式非法，应为 YYYY-MM-DD");
        }
    }

    private String requireIdentifier(String value, String label) {
        if (value == null || !value.matches("[a-zA-Z0-9-]{1,128}")) {
            throw new IllegalArgumentException(label + "格式非法");
        }
        return value;
    }

    private void requireFileId(String fileId) {
        if (fileId == null || !fileId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("素材文件 ID 格式非法");
        }
    }
}
