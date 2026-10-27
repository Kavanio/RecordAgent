package butvan.agent.agents.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentImagesTest {

    @Test
    void sniffsSupportedFormatsFromFileHeaders() {
        assertEquals("image/png", AgentImages.mediaType(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)));
        assertEquals("image/jpeg", AgentImages.mediaType(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals("image/gif", AgentImages.mediaType(bytes('G', 'I', 'F', '8', '9', 'a')));
        assertEquals("image/webp", AgentImages.mediaType(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')));
    }

    @Test
    void rejectsNonImagesEvenWhenTheCallerClaimsImage() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentImages.parse("%PDF-1.7 并不是图片".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(AgentImages.mediaType(null));
    }

    @Test
    void oversizedImageIsRejectedInsteadOfTruncated() {
        byte[] data = new byte[AgentImageAttachment.MAX_BYTES + 1];
        data[0] = (byte) 0x89; data[1] = 'P'; data[2] = 'N'; data[3] = 'G';
        data[4] = 0x0D; data[5] = 0x0A; data[6] = 0x1A; data[7] = 0x0A;
        assertThrows(IllegalArgumentException.class, () -> AgentImages.parse(data));
    }

    private static byte[] bytes(int... values) {
        byte[] data = new byte[Math.max(values.length, 12)];
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i];
        return data;
    }
}
