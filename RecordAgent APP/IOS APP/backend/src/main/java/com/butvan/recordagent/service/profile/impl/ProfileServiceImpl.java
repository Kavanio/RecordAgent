package com.butvan.recordagent.service.profile.impl;

import com.butvan.recordagent.dto.profile.ProfileDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.profile.ProfileRepository;
import com.butvan.recordagent.service.file.FileAssetService;
import com.butvan.recordagent.service.profile.ProfileService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 资料修改与图片所有权同事务校验；空昵称保留当前昵称。 */
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {
    private final ProfileRepository store;
    private final FileAssetService files;

    @Override
    @Transactional(readOnly = true)
    public ProfileDtos.Appearance appearance(UUID owner) {
        return store.appearance(owner)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "user not found"));
    }

    @Override
    @Transactional
    public ProfileDtos.Appearance update(UUID owner, ProfileDtos.Update request) {
        var current = appearance(owner);
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "profile version changed");
        }
        if (request.avatarFileId() != null) files.requireOwned(owner, request.avatarFileId());
        if (request.coverFileId() != null) files.requireOwned(owner, request.coverFileId());
        String name =
                request.nickname() == null || request.nickname().isBlank()
                        ? current.nickname()
                        : request.nickname().strip();
        try {
            if (!store.updateName(owner, name, request.expectedVersion())) {
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "profile version changed");
            }
            store.setMedia(owner, request.avatarFileId(), request.coverFileId());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "profile name exists");
        }
        return appearance(owner);
    }
}
