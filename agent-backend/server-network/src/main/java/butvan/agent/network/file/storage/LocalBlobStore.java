package butvan.agent.network.file.storage;

import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.config.database.LocalDatabaseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 使用应用数据目录保存二进制对象，并通过临时文件与原子移动避免暴露半份内容。 */
@Component
public class LocalBlobStore implements BlobStore {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String OBJECT_PREFIX = "files/objects/";
    private static final String LEGACY_PREFIX = "records/attachments/";
    private static final String MATERIALS_PREFIX = "materials/";

    private final LocalDatabaseProperties databaseProperties;
    private final AgentStorageProperties agentStorageProperties;

    public LocalBlobStore(LocalDatabaseProperties databaseProperties) {
        this(databaseProperties, null);
    }

    @Autowired
    public LocalBlobStore(LocalDatabaseProperties databaseProperties,
                          AgentStorageProperties agentStorageProperties) {
        this.databaseProperties = databaseProperties;
        this.agentStorageProperties = agentStorageProperties;
    }

    @Override
    public StoredBlob put(String objectId, InputStream content, long maximumBytes) {
        requireObjectId(objectId);
        String storageKey = OBJECT_PREFIX + objectId.substring(0, 2) + "/" + objectId.substring(2, 4) + "/" + objectId;
        return writeToStorageKey(storageKey, content, maximumBytes);
    }

    @Override
    public StoredBlob put(String objectId, String relativeDirectory, String extension,
                          InputStream content, long maximumBytes) {
        requireObjectId(objectId);
        String safeDir = sanitizeRelativeDirectory(relativeDirectory);
        String safeExt = sanitizeExtension(extension);
        String storageKey = MATERIALS_PREFIX + safeDir + "/" + objectId + "." + safeExt;
        return writeToStorageKey(storageKey, content, maximumBytes);
    }

    private StoredBlob writeToStorageKey(String storageKey, InputStream content, long maximumBytes) {
        if (content == null) throw new IllegalArgumentException("文件内容不能为空");
        if (maximumBytes < 1) throw new IllegalArgumentException("文件大小上限必须大于零");

        Path target = resolve(storageKey);
        Path stagingDirectory = dataRoot().resolve("files/staging");
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            Files.createDirectories(stagingDirectory);
            temporary = Files.createTempFile(stagingDirectory, ".upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copy(content, temporary, maximumBytes, digest);
            moveAtomically(temporary, target);
            temporary = null;
            return new StoredBlob(storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件写入失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        } finally {
            deleteTemporary(temporary);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        Path target = resolve(storageKey);
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件内容不存在");
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件读取失败", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolve(storageKey);
        if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("文件对象不能是符号链接");
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("本地文件删除失败", exception);
        }
    }

    private long copy(InputStream source, Path target, long maximumBytes, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) throw new IllegalArgumentException("文件超过允许的大小上限");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        return total;
    }

    private Path resolve(String storageKey) {
        if (storageKey == null) {
            throw new IllegalArgumentException("本地文件存储键非法");
        }
        if (storageKey.startsWith(MATERIALS_PREFIX)) {
            Path materialsRoot = materialsRoot();
            String relative = storageKey.substring(MATERIALS_PREFIX.length());
            Path resolved = materialsRoot.resolve(relative).normalize();
            if (!resolved.startsWith(materialsRoot) || resolved.equals(materialsRoot)) {
                throw new IllegalArgumentException("本地素材存储键越界");
            }
            return resolved;
        }
        if (!storageKey.startsWith(OBJECT_PREFIX) && !storageKey.startsWith(LEGACY_PREFIX)) {
            throw new IllegalArgumentException("本地文件存储键非法");
        }
        Path root = dataRoot();
        Path resolved = root.resolve(storageKey).normalize();
        Path allowedRoot = storageKey.startsWith(OBJECT_PREFIX)
                ? root.resolve("files/objects")
                : root.resolve("records/attachments");
        if (!resolved.startsWith(allowedRoot)) throw new IllegalArgumentException("本地文件存储键越界");
        return resolved;
    }

    private Path materialsRoot() {
        if (agentStorageProperties != null) {
            return agentStorageProperties.getMaterialsDirectory().toAbsolutePath().normalize();
        }
        return dataRoot().resolve("materials").toAbsolutePath().normalize();
    }

    private String sanitizeRelativeDirectory(String relativeDirectory) {
        if (relativeDirectory == null || relativeDirectory.isBlank()) {
            throw new IllegalArgumentException("素材归档目录不能为空");
        }
        String normalized = relativeDirectory.replace('\\', '/').replaceAll("^/+|/+$", "");
        String[] segments = normalized.split("/");
        if (segments.length == 0) throw new IllegalArgumentException("素材归档目录非法");
        for (String segment : segments) {
            if (!segment.matches("[a-zA-Z0-9_-]{1,80}")) {
                throw new IllegalArgumentException("素材归档目录段格式非法: " + segment);
            }
        }
        return String.join("/", segments);
    }

    private String sanitizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("素材文件扩展名不能为空");
        }
        String clean = extension.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith(".")) clean = clean.substring(1);
        if (!clean.matches("[a-z0-9]{1,10}")) {
            throw new IllegalArgumentException("素材文件扩展名格式非法");
        }
        return clean;
    }

    private Path dataRoot() {
        Path databasePath = databaseProperties.getPath().toAbsolutePath().normalize();
        Path parent = databasePath.getParent();
        if (parent == null) throw new IllegalStateException("SQLite 数据库路径必须包含父目录");
        return parent;
    }

    private void requireObjectId(String objectId) {
        if (objectId == null || !objectId.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("文件对象 ID 格式非法");
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteTemporary(Path temporary) {
        if (temporary == null) return;
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 主异常已包含写入失败原因；残留 staging 文件可由后续清理任务回收。
        }
    }
}
