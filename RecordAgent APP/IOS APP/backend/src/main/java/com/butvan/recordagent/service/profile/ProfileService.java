package com.butvan.recordagent.service.profile;

import com.butvan.recordagent.dto.profile.ProfileDtos;
import java.util.UUID;

/** 个人资料更新与文件所有权校验边界。 */
public interface ProfileService {
    /** 读取本人当前资料。 */
    ProfileDtos.Appearance appearance(UUID owner);

    /** 原子更新昵称与图片绑定。 */
    ProfileDtos.Appearance update(UUID owner, ProfileDtos.Update request);
}
