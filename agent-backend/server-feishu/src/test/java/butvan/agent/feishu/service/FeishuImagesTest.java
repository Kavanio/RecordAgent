package butvan.agent.feishu.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeishuImagesTest {

    @Test void sniffsSupportedFormatsFromFileHeaders() {
        assertEquals("image/png", FeishuImages.mediaType(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)));
        assertEquals("image/jpeg", FeishuImages.mediaType(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals("image/gif", FeishuImages.mediaType(bytes('G', 'I', 'F', '8', '9', 'a')));
        assertEquals("image/webp", FeishuImages.mediaType(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')));
    }

    @Test void rejectsNonImagesEvenWhenThePlatformClaimsImage() {
        // 扩展名或平台声明不可信，只认文件头；无法识别的字节一律拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> FeishuImages.parse("%PDF-1.7 并不是图片".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(FeishuImages.mediaType(null));
    }

    @Test void oversizedImageIsRejectedInsteadOfTruncated() {
        byte[] data = new byte[butvan.agent.agents.agent.AgentImageAttachment.MAX_BYTES + 1];
        data[0] = (byte) 0x89; data[1] = 'P'; data[2] = 'N'; data[3] = 'G';
        data[4] = 0x0D; data[5] = 0x0A; data[6] = 0x1A; data[7] = 0x0A;
        assertThrows(IllegalArgumentException.class, () -> FeishuImages.parse(data));
    }

    private static byte[] bytes(int... values) {
        byte[] data = new byte[Math.max(values.length, 12)];
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i];
        return data;
    }
}
