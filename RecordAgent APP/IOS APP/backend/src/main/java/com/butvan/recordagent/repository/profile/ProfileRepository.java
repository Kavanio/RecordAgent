package com.butvan.recordagent.repository.profile;

import com.butvan.recordagent.dto.profile.ProfileDtos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账户昵称与个人媒体绑定的受控 SQL。 */
@Repository
@RequiredArgsConstructor
public class ProfileRepository {
    private final JdbcTemplate jdbc;

    /** 从稳定用户 ID 读取本人资料。 */
    public Optional<ProfileDtos.Appearance> appearance(UUID owner) {
        List<ProfileDtos.Appearance> rows = jdbc.query("SELECT u.display_name,m.avatar_file_id,"
                + "m.background_file_id,u.version FROM record.app_user u "
                + "LEFT JOIN record.user_media m ON m.owner_id=u.id "
                + "WHERE u.id=? AND u.status='active'", (rs, index) ->
                new ProfileDtos.Appearance(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4)), owner);
        return rows.stream().findFirst();
    }

    /** 用预期版本阻止并发设备覆盖资料。 */
    public boolean updateName(UUID owner, String name, long expectedVersion) {
        return jdbc.update("UPDATE record.app_user SET display_name=?,display_name_normalized=lower(?),"
                + "version=version+1,updated_at=now() WHERE id=? AND version=? AND status='active'",
                name, name, owner, expectedVersion) == 1;
    }

    /** 图片引用只保存已经过服务层所有权校验的文件 ID。 */
    public void setMedia(UUID owner, UUID avatar, UUID cover) {
        jdbc.update("INSERT INTO record.user_media(owner_id,avatar_file_id,background_file_id) "
                + "VALUES(?,?,?) ON CONFLICT(owner_id) DO UPDATE SET "
                + "avatar_file_id=excluded.avatar_file_id,background_file_id=excluded.background_file_id,"
                + "version=record.user_media.version+1,updated_at=now()", owner, avatar, cover);
    }
}
