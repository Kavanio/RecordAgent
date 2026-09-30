package com.butvan.recordagent.service.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.butvan.recordagent.config.BusinessProperties;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.file.FileAssetRepository;
import com.butvan.recordagent.service.file.impl.FileAssetServiceImpl;
import com.butvan.recordagent.storage.file.BlobStore;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 验证图片上传在分配二进制前拒绝超限输入，并回收元数据写入失败的对象。 */
class FileAssetServiceImplTest {
    private final BlobStore blobs = mock(BlobStore.class);
    private final FileAssetRepository metadata = mock(FileAssetRepository.class);
    private final FileAssetServiceImpl service =
            new FileAssetServiceImpl(new BusinessProperties(), blobs, metadata);

    @Test
    void rejectsOversizedBase64BeforeAccessingStorage() {
        String oversized = "A".repeat(2_666_669);

        assertThatThrownBy(() -> service.uploadJpeg(UUID.randomUUID(), oversized))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(ApiErrorCode.INVALID_ARGUMENT));
        verifyNoInteractions(blobs, metadata);
    }

    @Test
    void removesBlobWhenMetadataWriteFails() throws Exception {
        UUID owner = UUID.randomUUID();
        String image =
                Base64.getEncoder()
                        .encodeToString(
                                new byte[] {(byte) 0xff, (byte) 0xd8, 0, (byte) 0xff, (byte) 0xd9});
        doThrow(new DataIntegrityViolationException("test failure"))
                .when(metadata)
                .insert(any(), eq(owner), anyLong(), anyString());

        assertThatThrownBy(() -> service.uploadJpeg(owner, image))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(ApiErrorCode.SERVICE_UNAVAILABLE));
        verify(blobs).delete(any());
    }
}
