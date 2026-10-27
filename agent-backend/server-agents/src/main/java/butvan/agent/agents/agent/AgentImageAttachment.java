package butvan.agent.agents.agent;

import java.util.Base64;
import java.util.Set;

/**
 * 随一轮用户消息一次性传入的图片附件。
 *
 * <p>只在内存中短暂存在，禁止写入 transcript、checkpoint、Token 用量记录或日志；
 * 需要长期保留时必须先由文件资产服务登记为稳定文件 ID。图片按 Base64 内容块
 * 与本轮文本一起交给模型，供应商是否真正支持视觉仍由模型能力决定。</p>
 *
 * @param mediaType 图片媒体类型，例如 {@code image/png}
 * @param data      图片原始字节
 */
public record AgentImageAttachment(String mediaType, byte[] data) {

    /** 单张图片上限；超过后拒绝而不是悄悄截断或压缩。 */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    /** 仅接受模型适配器普遍支持的四种位图格式。 */
    private static final Set<String> SUPPORTED = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    public AgentImageAttachment {
        if (mediaType == null || !SUPPORTED.contains(mediaType.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("不支持的图片格式，仅支持 PNG、JPEG、WebP 与 GIF");
        }
        mediaType = mediaType.toLowerCase(java.util.Locale.ROOT);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("图片超过 10 MiB 上限");
        }
        data = data.clone();
    }

    /** 防御性复制，避免调用方在模型调用期间改写字节。 */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /** 供模型适配器使用的 Base64 内容，只在内存中生成。 */
    public String base64() {
        return Base64.getEncoder().encodeToString(data);
    }

    /** 禁止把图片字节写进日志或异常信息。 */
    @Override
    public String toString() {
        return "AgentImageAttachment[mediaType=" + mediaType + ", bytes=" + data.length + "]";
    }
}
