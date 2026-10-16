package com.butvan.recordagent.controller.file;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.file.FileDtos;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.file.FileAssetService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受限个人图片上传和本人图片读取。 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileAssetController {
    private final FileAssetService files;

    @ApiLog("上传个人资料图片")
    @PostMapping
    public FileDtos.Uploaded upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody FileDtos.Upload request) {
        return new FileDtos.Uploaded(files.uploadJpeg(user.userId(), request.jpegBase64()));
    }

    @ApiLog("读取本人图片")
    @GetMapping(value = "/{id}/content", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> content(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(files.read(user.userId(), id));
    }
}
