package butvan.agent.feishu.service;

import butvan.agent.agents.agent.AgentImageAttachment;

/** 按文件头判定图片格式，不信任平台返回的文件名或扩展名。 */
public final class FeishuImages {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF = {'G', 'I', 'F', '8'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    private FeishuImages() { }

    /**
     * 校验并包装成模型可用的图片附件；大小与格式不符合要求时抛出中文异常。
     *
     * @param data 图片原始字节
     * @return 图片附件
     */
    public static AgentImageAttachment parse(byte[] data) {
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
