package butvan.agent.agents.review;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.agents.usage.SystemUsageLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 同一次配置读取后比对接收方身份，配置竞态不会先把正文发到新地址。 */
class ReviewConnectionIdentityTest {
    @TempDir Path directory;
    @Test void changedReceiverIsRejectedBeforeModelCreationOrUsage() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, """
                {"providers":[{"id":"p","type":"custom","baseUrl":"https://receiver.example/v1","protocol":"openai",
                "isEnabled":true,"apiKey":"synthetic-only","models":[{"name":"m","modelName":"m"}]}]}
                """);
        var config = mock(LocalConfigService.class); when(config.getConfigPath()).thenReturn(file);
        var ledger = mock(SystemUsageLedger.class);
        var generator = new ModelReviewGenerator(config, ledger);
        var input = new ReviewGenerationRequest("LIFE_DAILY", "当天", "部分材料", List.of(), List.of(), List.of(), List.of(), List.of(),
                5000, 1200, 60000, "p", "m", List.of(new ReviewGenerationRequest.Narrative("id", "JOURNAL", "r", "2026-10-30", "固定虚构内容", false)), "old-receiver");
        var failure = assertThrows(ReviewGenerator.ReviewGenerationException.class, () -> generator.generate(input));
        assertTrue(failure.getMessage().contains("未发送正文")); verifyNoInteractions(ledger);
    }
    @Test void normalizedIdentityIncludesModelAndProtocolButNoCredential() {
        String original = ReviewConnectionIdentity.fingerprint("p", "m", "custom", "openai", "https://example.com/v1/");
        assertEquals(original, ReviewConnectionIdentity.fingerprint("p", "m", "custom", "openai", "https://example.com/v1"));
        assertNotEquals(original, ReviewConnectionIdentity.fingerprint("p", "other", "custom", "openai", "https://example.com/v1"));
        assertNotEquals(original, ReviewConnectionIdentity.fingerprint("p", "m", "custom", "anthropic", "https://example.com/v1"));
    }
}
