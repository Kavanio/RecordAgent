package butvan.agent.agents.agent;

/**
 * 图片内容的统一校验入口：只认文件头，不信任扩展名或调用方声明的媒体类型。
 *
 * <p>桌面端上传、飞书下载等所有外部来源都必须经过这里，避免各处自建一套格式判断。</p>
 */
public final class AgentImages {
    /** 两端统一的轮次数量、原始字节总量与纯图片消息说明。 */
    public static final int MAX_COUNT = 4;
    public static final int MAX_TOTAL_BYTES = 12 * 1024 * 1024;
    public static final int MAX_BASE64_CHARS = ((AgentImageAttachment.MAX_BYTES + 2) / 3) * 4;
    public static final String IMAGE_ONLY_PROMPT = "用户发送了一张图片，请识别并回应图片内容。";

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF = {'G', 'I', 'F', '8'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private AgentImages() { }

    /**
     * 校验并包装成模型可用的图片附件。
     *
     * @param data 图片原始字节
     * @return 图片附件
     * @throws IllegalArgumentException 格式不受支持、内容为空或超过大小上限
     */
    public static AgentImageAttachment parse(byte[] data) {
        if (data != null && data.length > AgentImageAttachment.MAX_BYTES) {
            throw new IllegalArgumentException("单张图片不能超过 10 MiB");
        }
        String mediaType = mediaType(data);
        if (mediaType == null) {
            throw new IllegalArgumentException("不支持的图片格式，仅支持 PNG、JPEG、WebP 与 GIF");
        }
        return new AgentImageAttachment(mediaType, data);
    }

    /** 返回文件头对应的媒体类型；无法识别时返回 null。 */
    static String mediaType(byte[] data) {
        if (data == null || data.length < 12) return null;
        if (startsWith(data, PNG)) return "image/png";
        if (startsWith(data, JPEG)) return "image/jpeg";
        if (startsWith(data, GIF) && (data[4] == '7' || data[4] == '9') && data[5] == 'a') return "image/gif";
        if (startsWith(data, RIFF) && startsWith(data, WEBP, 8)) return "image/webp";
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return startsWith(data, prefix, 0);
    }

    private static boolean startsWith(byte[] data, byte[] prefix, int offset) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) return false;
        }
        return true;
    }
}
