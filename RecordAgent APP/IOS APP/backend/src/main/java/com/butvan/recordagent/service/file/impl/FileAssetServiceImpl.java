package com.butvan.recordagent.service.file.impl;

import com.butvan.recordagent.filter.RequestIdFilter;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.file.FileAssetRepository;
import com.butvan.recordagent.service.file.FileAssetService;
import com.butvan.recordagent.storage.file.BlobStore;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 图片输入严格限制大小和 JPEG 签名，不公开底层存储路径。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileAssetServiceImpl implements FileAssetService {
    private static final int MAX_IMAGE_BYTES = 2_000_000;
    private final BlobStore blobs;
    private final FileAssetRepository metadata;

    @Override
    public UUID uploadJpeg(UUID owner, String base64) {
        byte[] content;
        try { content = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException exception) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid image encoding");
        }
        if (content.length < 4 || content.length > MAX_IMAGE_BYTES
                || (content[0] & 0xff) != 0xff || (content[1] & 0xff) != 0xd8
                || (content[content.length - 2] & 0xff) != 0xff
                || (content[content.length - 1] & 0xff) != 0xd9) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid jpeg image");
        }
        UUID id = UUID.randomUUID();
        try {
            blobs.write(id, content);
            metadata.insert(id, owner, content.length, sha256(content));
            return id;
        } catch (Exception exception) {
            try { blobs.delete(id); }
            catch (IOException cleanupFailure) {
                log.error("File upload cleanup failed: requestId={} errorType={}",
                        RequestIdFilter.currentRequestId(), cleanupFailure.getClass().getSimpleName());
            }
            log.error("File upload failed: requestId={} errorType={}",
                    RequestIdFilter.currentRequestId(), exception.getClass().getSimpleName());
            throw new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "file unavailable");
        }
    }

    @Override
    public byte[] read(UUID owner, UUID id) {
        requireOwned(owner, id);
        try { return blobs.read(id); }
        catch (IOException exception) {
            log.error("File read failed: requestId={} errorType={}",
                    RequestIdFilter.currentRequestId(), exception.getClass().getSimpleName());
            throw new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "file unavailable");
        }
    }

    @Override
    public void requireOwned(UUID owner, UUID id) {
        if (!metadata.owned(owner, id)) throw new ApiException(ApiErrorCode.NOT_FOUND, "file not found");
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
