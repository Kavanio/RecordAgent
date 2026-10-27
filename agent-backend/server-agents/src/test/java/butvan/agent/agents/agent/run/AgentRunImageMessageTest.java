package butvan.agent.agents.agent.run;

import butvan.agent.agents.agent.AgentImageAttachment;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunImageMessageTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Test
    void imagesBecomeBase64BlocksAlongsideTheText() {
        AgentRun run = new AgentRun("session", "user", "turn", null);
        Msg message = run.currentUserMessage("这是什么", List.of(), List.of(new AgentImageAttachment("image/png", PNG)));

        assertEquals("这是什么", message.getTextContent());
        assertTrue(message.hasContentBlocks(TextBlock.class));
        List<ImageBlock> images = message.getContentBlocks(ImageBlock.class);
        assertEquals(1, images.size());
        Base64Source source = (Base64Source) images.getFirst().getSource();
        assertEquals("image/png", source.getMediaType());
        // 图片以 Base64 内容块交给模型，绝不进入正文或持久化快照。
        assertEquals(new AgentImageAttachment("image/png", PNG).base64(), source.getData());
        assertFalse(message.getTextContent().contains("iVBOR"));
    }

    @Test
    void textOnlyTurnKeepsASingleTextBlock() {
        AgentRun run = new AgentRun("session", "user", "turn", null);
        Msg message = run.currentUserMessage("hello", List.of());

        assertEquals(1, message.getContent().size());
        assertTrue(message.getContentBlocks(ImageBlock.class).isEmpty());
    }

    @Test
    void unsupportedFormatAndOversizedImagesAreRejectedEarly() {
        assertThrows(IllegalArgumentException.class, () -> new AgentImageAttachment("image/tiff", PNG));
        assertThrows(IllegalArgumentException.class, () -> new AgentImageAttachment("image/png", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentImageAttachment("image/png", new byte[AgentImageAttachment.MAX_BYTES + 1]));
    }

    @Test
    void attachmentToStringNeverLeaksImageBytes() {
        String rendered = new AgentImageAttachment("image/png", PNG).toString();
        assertTrue(rendered.contains("bytes=12"));
        assertFalse(rendered.contains("iVBOR"));
    }
}
