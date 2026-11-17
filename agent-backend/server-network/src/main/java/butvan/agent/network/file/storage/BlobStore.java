package butvan.agent.network.file.storage;

import java.io.InputStream;

/**
 * 二进制内容存储 seam。
 *
 * <p>实现负责限制实际读取量、计算完整性摘要并隐藏供应商路径语义。</p>
 */
public interface BlobStore {

    /** 写入一个新对象；超过上限时必须终止并清理临时内容。 */
    StoredBlob put(String objectId, InputStream content, long maximumBytes);

    /** 写入一个按相对目录与扩展名归档的对象；默认回退到扁平对象写入。 */
    default StoredBlob put(String objectId, String relativeDirectory, String extension,
                           InputStream content, long maximumBytes) {
        return put(objectId, content, maximumBytes);
    }

    /** 打开对象内容；调用方负责关闭返回的流。 */
    InputStream open(String storageKey);

    /** 删除对象；对象已经不存在时按成功处理。 */
    void delete(String storageKey);

    /** 已持久化对象的不可变定位信息。 */
    record StoredBlob(String storageKey, long sizeBytes, String sha256) {
    }
}
