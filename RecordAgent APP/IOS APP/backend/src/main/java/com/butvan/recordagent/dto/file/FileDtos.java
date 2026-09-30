package com.butvan.recordagent.dto.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 当前个人资料图片的受限上传契约。 */
public final class FileDtos {
    private FileDtos() {}

    public record Upload(@NotBlank @Size(max = 2800000) String jpegBase64) {}

    public record Uploaded(UUID id) {}
}
