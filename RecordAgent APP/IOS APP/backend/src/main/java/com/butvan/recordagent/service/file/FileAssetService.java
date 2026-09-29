package com.butvan.recordagent.service.file;

import java.util.UUID;

/** 文件资产服务负责校验、所有权和二进制生命周期。 */
public interface FileAssetService {
    /** 上传受限 JPEG 图片。 */
    UUID uploadJpeg(UUID owner, String base64);
    /** 读取本人文件内容。 */
    byte[] read(UUID owner, UUID id);
    /** 确认文件属于本人且可用。 */
    void requireOwned(UUID owner, UUID id);
}
