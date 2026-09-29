package com.butvan.recordagent.storage.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本机开发阶段的文件对象存储；目录由部署配置提供。 */
@Component
public class LocalBlobStore implements BlobStore {
    private final Path root;

    public LocalBlobStore(@Value("${recordagent.files.root:./data/files/objects}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void write(UUID id, byte[] content) throws IOException {
        Files.createDirectories(root);
        Files.write(path(id), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public byte[] read(UUID id) throws IOException { return Files.readAllBytes(path(id)); }

    @Override
    public void delete(UUID id) throws IOException { Files.deleteIfExists(path(id)); }

    private Path path(UUID id) { return root.resolve(id.toString()).normalize(); }
}
