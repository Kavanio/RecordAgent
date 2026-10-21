package butvan.agent.agents.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** 验证自定义地址边界；错误提示不回显可能包含密钥的原始 URL。 */
class ModelEndpointTest {
    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/model", "ftp://example.com", "https://user:secret@example.com",
            "https://example.com?key=secret", "https://example.com#secret", "not-a-url",
            "https://example.com/v1/chat/completions", "https://example.com/v1/messages/"})
    void rejectsInvalidOrCredentialBearingUrls(String value) {
        var exception = assertThrows(IllegalArgumentException.class, () -> ModelEndpoint.normalize(value));
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test
    void preservesPathPrefixesAndAvoidsDuplicateAnthropicVersion() {
        assertEquals("http://localhost:11434/v1", ModelEndpoint.normalize(" http://localhost:11434/v1/ "));
        assertEquals("https://example.com/gateway", ModelEndpoint.anthropicBaseUrl("https://example.com/gateway/v1/"));
        assertNull(ModelEndpoint.normalize(""));
        assertNull(ModelEndpoint.anthropicBaseUrl(null));
        assertEquals("anthropic", ModelEndpoint.protocol(" Anthropic "));
        assertThrows(IllegalArgumentException.class, () -> ModelEndpoint.protocol(null));
        assertThrows(IllegalArgumentException.class, () -> ModelFactory.create(
                new ModelSelector("custom", "model", "test-key", 0.7, true, null, "openai")));
    }
}
