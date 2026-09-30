package com.butvan.recordagent.repository.file;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 文件元数据和所有权的 PostgreSQL 适配。 */
@Repository
@RequiredArgsConstructor
public class FileAssetRepository {
    private final JdbcTemplate jdbc;

    /** 二进制已落盘后记录可用元数据。 */
    public void insert(UUID id, UUID owner, long size, String digest) {
        jdbc.update(
                "INSERT INTO record.file_asset(id,owner_id,original_name,media_type,size_bytes,sha256,"
                        + "backend_type,storage_key,status) VALUES(?,?,'profile.jpg','image/jpeg',?,?,"
                        + "'local',?,'available')",
                id,
                owner,
                size,
                digest,
                id.toString());
    }

    /** 查询本人可用文件。 */
    public boolean owned(UUID owner, UUID id) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM record.file_asset "
                                + "WHERE owner_id=? AND id=? AND status='available'",
                        Integer.class,
                        owner,
                        id);
        return count != null && count > 0;
    }

    /** 上传失败时清理未绑定的元数据。 */
    public void delete(UUID owner, UUID id) {
        jdbc.update("DELETE FROM record.file_asset WHERE owner_id=? AND id=?", owner, id);
    }
}
