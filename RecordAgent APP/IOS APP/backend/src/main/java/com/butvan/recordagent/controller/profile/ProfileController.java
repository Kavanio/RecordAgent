package com.butvan.recordagent.controller.profile;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.profile.ProfileDtos;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.profile.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 昵称与头像/封面的本人资料入口。 */
@RestController
@RequestMapping("/api/v1/me/appearance")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profiles;

    @ApiLog("读取本人资料外观")
    @GetMapping
    public ProfileDtos.Appearance appearance(@AuthenticationPrincipal AuthenticatedUser user) {
        return profiles.appearance(user.userId());
    }

    @ApiLog("更新本人昵称和图片")
    @PatchMapping
    public ProfileDtos.Appearance update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ProfileDtos.Update request) {
        return profiles.update(user.userId(), request);
    }
}
