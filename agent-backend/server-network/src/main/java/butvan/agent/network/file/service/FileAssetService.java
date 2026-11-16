package butvan.agent.network.file.service;

import butvan.agent.network.file.model.FileAsset;
import butvan.agent.network.file.repository.FileAssetRepository;
import butvan.agent.network.file.storage.BlobStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 文件资产领域入口，集中维护命名、大小、所有权、业务绑定和内容生命周期。 */
@Service
@RequiredArgsConstructor
public class FileAssetService {
    public static final String DOMAIN_RECORD = "RECORD";
    public static final String DOMAIN_ACCOUNT = "ACCOUNT";
    public static final String ROLE_ATTACHMENT = "ATTACHMENT";
    public static final String ROLE_AVATAR = "AVATAR";
    public static final String ROLE_COVER = "COVER";
    private static final String LOCAL_BACKEND = "LOCAL";
    private static final String AVAILABLE = "AVAILABLE";

    private final FileAssetRepository repository;
    private final BlobStore blobStore;

    /** 写入内容并在同一领域操作中建立业务绑定。 */
    @Transactional
    public FileAsset createAndBind(String ownerId, String domainType, String domainId, String role,
                                   String originalName, String mediaType, InputStream content, long maximumBytes) {
        requireIdentifier(ownerId, "用户 ID");
        requireIdentifier(domainId, "业务对象 ID");
        requireBindingValue(domainType, "业务类型");
        requireBindingValue(role, "文件角色");

        String fileId = UUID.randomUUID().toString().replace("-", "");
        BlobStore.StoredBlob stored = blobStore.put(fileId, content, maximumBytes);
        Instant now = Instant.now();
        FileAsset asset = new FileAsset(fileId, ownerId, safeName(originalName), safeMediaType(mediaType),
                stored.sizeBytes(), stored.sha256(), LOCAL_BACKEND, null, stored.storageKey(), AVAILABLE,
                now, now, null);
        try {
            repository.insert(asset);
            repository.bind(UUID.randomUUID().toString(), fileId, ownerId, domainType, domainId, role, now);
            return asset;
        } catch (RuntimeException exception) {
            try {
                blobStore.delete(stored.storageKey());
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    public List<FileAsset> list(String ownerId, String domainType, String domainId, String role) {
        return repository.findBound(ownerId, domainType, domainId, role);
    }

    public FileAsset get(String ownerId, String fileId, String domainType, String domainId, String role) {
        return repository.findBoundFile(ownerId, fileId, domainType, domainId, role)
                .orElseThrow(() -> new IllegalArgumentException("文件不存在"));
    }

    public InputStream open(String ownerId, String fileId, String domainType, String domainId, String role) {
        return blobStore.open(get(ownerId, fileId, domainType, domainId, role).storageKey());
    }

    /** 删除业务绑定；没有其他引用时再回收实际内容。 */
    public void unbind(String ownerId, String fileId, String domainType, String domainId, String role) {
        FileAsset asset = get(ownerId, fileId, domainType, domainId, role);
        if (!repository.unbind(ownerId, fileId, domainType, domainId, role)) {
            throw new IllegalArgumentException("文件不存在");
        }
        if (repository.countBindings(fileId) > 0) return;
        repository.markDeleting(ownerId, fileId, Instant.now());
        blobStore.delete(asset.storageKey());
        repository.deleteMetadata(ownerId, fileId);
    }

    public byte[] readAllBytes(String ownerId, String fileId, String domainType, String domainId, String role) {
        try (InputStream input = open(ownerId, fileId, domainType, domainId, role)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("文件内容读取失败", exception);
        }
    }

    private String safeName(String originalName) {
        String candidate = originalName == null || originalName.isBlank()
                ? "附件"
                : originalName.replace('\\', '/');
        try {
            candidate = Path.of(candidate).getFileName().toString().trim();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("文件名格式非法");
        }
        if (candidate.isEmpty() || candidate.length() > 255
                || candidate.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名长度或字符不合法");
        }
        return candidate;
    }

    private String safeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) return "application/octet-stream";
        String normalized = mediaType.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 150 || !normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
            return "application/octet-stream";
        }
        return normalized;
    }

    private void requireIdentifier(String value, String label) {
        if (value == null || !value.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException(label + " 格式非法");
    }

    private void requireBindingValue(String value, String label) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,31}")) throw new IllegalArgumentException(label + " 格式非法");
    }
}
