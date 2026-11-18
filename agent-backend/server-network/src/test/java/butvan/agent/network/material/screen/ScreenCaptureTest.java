package butvan.agent.network.material.screen;

import butvan.agent.agents.agent.AgentImageAttachment;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ScreenCaptureTest {

    @Test
    void compressesOversizedHighEntropyPngWithinMaxBytes() throws Exception {
        // 高熵噪点大图会生成远超 10 MiB 的 PNG，验证会平稳降级为有界 JPEG。
        int width = 3200;
        int height = 2000;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(42);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (0xFF << 24) | (random.nextInt(0x1000000));
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);

        AgentImageAttachment attachment = ScreenCapture.encodeWithinLimit(image);
        assertEquals("image/jpeg", attachment.mediaType());
        assertTrue(attachment.sizeBytes() > 0 && attachment.sizeBytes() <= AgentImageAttachment.MAX_BYTES);
    }

    @Test
    void keepsSmallScreenshotsAsLosslessPng() throws Exception {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        AgentImageAttachment attachment = ScreenCapture.encodeWithinLimit(image);
        assertEquals("image/png", attachment.mediaType());
        assertTrue(attachment.sizeBytes() <= AgentImageAttachment.MAX_BYTES);
    }
}
