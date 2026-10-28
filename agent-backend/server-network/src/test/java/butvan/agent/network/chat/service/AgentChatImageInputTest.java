package butvan.agent.network.chat.service;

import butvan.agent.agents.agent.AgentImages;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.network.chat.dto.AgentChatRequest;
import butvan.agent.network.chat.dto.AgentChatRequest.AgentChatImage;
import butvan.agent.network.record.service.RecordService;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 桌面图片输入边界：不用真实配置、模型、磁盘或网络。 */
class AgentChatImageInputTest {
    private final ModelHolder model = mock(ModelHolder.class);
    private final RecordService records = mock(RecordService.class);
    private final AgentChatContextService service = new AgentChatContextService(records, model);

    private AgentChatRequest request(List<AgentChatImage> images) {
        return new AgentChatRequest("run", "session", "", "", List.of(), null, images);
    }

    private byte[] png(int length) {
        byte[] data = new byte[length];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        System.arraycopy(signature, 0, data, 0, signature.length);
        return data;
    }

    private AgentChatImage image(int length) {
        return new AgentChatImage("image/jpeg", Base64.getEncoder().encodeToString(png(length)));
    }

    @Test void checksVisionBeforeDecodingOrReadingReferences() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(List.of(new AgentChatImage("image/png", "invalid!")))));
        assertTrue(error.getMessage().contains("图片识别能力"));
        verifyNoInteractions(records);
    }

    @Test void usesActualFormatAndSuppliesImageOnlyPrompt() {
        when(model.supportsVision()).thenReturn(true);
        var call = service.prepare("owner", request(List.of(image(32))));
        assertEquals("image/png", call.images().getFirst().mediaType());
        assertEquals(32, call.images().getFirst().sizeBytes());
        assertEquals(AgentImages.IMAGE_ONLY_PROMPT, call.context());
        assertEquals(AgentImages.IMAGE_ONLY_PROMPT, call.content());
    }

    @Test void rejectsInvalidBase64AndNonImageContent() {
        when(model.supportsVision()).thenReturn(true);
        var invalid = new AgentChatImage("image/png", "not-base64!");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(List.of(invalid)))).getMessage().contains("Base64"));
        var fake = new AgentChatImage("image/png", Base64.getEncoder().encodeToString(new byte[32]));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(List.of(fake)))).getMessage().contains("格式"));
    }

    @Test void rejectsTooManyOrEmptyImages() {
        when(model.supportsVision()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(Collections.nCopies(5, image(32)))));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(List.of(new AgentChatImage("image/png", "")))));
    }

    @Test void rejectsEncodedSizeBeforeAllocatingDecodedBytes() {
        when(model.supportsVision()).thenReturn(true);
        var oversized = new AgentChatImage("image/png", "A".repeat(AgentImages.MAX_BASE64_CHARS + 1));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(List.of(oversized)))).getMessage().contains("10 MiB"));
    }

    @Test void totalBudgetIsInclusiveAndCheckedBeforeAnyDecoding() {
        when(model.supportsVision()).thenReturn(true);
        var sixMiB = image(6 * 1024 * 1024);
        assertEquals(2, service.prepare("owner", request(List.of(sixMiB, sixMiB))).images().size());
        var extra = image(32);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.prepare("owner", request(List.of(sixMiB, sixMiB, extra))))
                .getMessage().contains("12 MiB"));
    }

    @Test void textOnlyRequestsDoNotRequireVision() {
        assertTrue(service.prepare("owner", new AgentChatRequest("session", "hello", "hello", List.of(), null))
                .images().isEmpty());
        verifyNoInteractions(model);
    }
}
