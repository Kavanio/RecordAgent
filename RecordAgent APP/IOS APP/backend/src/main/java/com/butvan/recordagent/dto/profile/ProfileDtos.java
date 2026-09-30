package com.butvan.recordagent.dto.profile;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 昵称和个人图片的版本化资料契约。 */
public final class ProfileDtos {
    private ProfileDtos() {}

    public record Appearance(String nickname, UUID avatarFileId, UUID coverFileId, long version) {}
    public record Update(@Size(max = 32) String nickname, UUID avatarFileId, UUID coverFileId,
            @NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion) {}
}
