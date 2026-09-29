package com.butvan.recordagent.storage.file;

import java.io.IOException;
import java.util.UUID;

/** 二进制内容存储边界，业务服务只持有稳定文件 ID。 */
public interface BlobStore {
    /** 保存一个新对象。 */
    void write(UUID id, byte[] content) throws IOException;
    /** 读取已有对象。 */
    byte[] read(UUID id) throws IOException;
    /** 清理上传失败的对象。 */
    void delete(UUID id) throws IOException;
}
