package butvan.agent.network.review;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.network.review.service.ReviewModelCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从临时配置验证连接身份，不调用供应商、不改写配置、不泄露 Key。 */
class ReviewModelCatalogTest {
    @TempDir Path directory;
    private Path file;
    private ReviewModelCatalog catalog;
    private LocalConfigService config;

    @BeforeEach void setup() {
        file = directory.resolve("config.json");
        config = mock(LocalConfigService.class);
        when(config.getConfigPath()).thenReturn(file);
        catalog = new ReviewModelCatalog(config, new ObjectMapper());
    }

    @Test void missingOrMalformedConfigurationIsNeverInitializedOrRewritten() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("p1", "model-a"));
        assertFalse(Files.exists(file));
        String broken = "{\"apiKey\":\"PRIVATE_SECRET\",invalid-json";
        Files.writeString(file, broken);
        var failure = assertThrows(IllegalArgumentException.class, () -> catalog.resolve("p1", "model-a"));
        assertFalse(failure.getMessage().contains("PRIVATE_SECRET"));
        assertEquals(broken, Files.readString(file));
        verify(config, never()).loadFullConfigData();
    }

    @Test void keysDoNotAffectIdentityButRecipientProtocolAndModelDo() throws Exception {
        save("https://gateway.example.test/v1", "openai", "secret-one");
        var first = catalog.resolve("p1", "model-a");
        save("https://gateway.example.test/v1/", "openai", "secret-two");
        assertEquals(first.fingerprint(), catalog.resolve("p1", "model-a").fingerprint());
        save("https://other.example.test/v1", "openai", "secret-two");
        assertNotEquals(first.fingerprint(), catalog.resolve("p1", "model-a").fingerprint());
        save("https://gateway.example.test/v1", "anthropic", "secret-two");
        assertNotEquals(first.fingerprint(), catalog.resolve("p1", "model-a").fingerprint());
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("p1", "unregistered-model"));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("custom", "model-a"));
        assertFalse(new ObjectMapper().writeValueAsString(first).contains("secret"));
    }

    @Test void anthropicEquivalentEndpointHasSameIdentityAndCredentialUrlsAreRejected() throws Exception {
        save("https://gateway.example.test/v1", "anthropic", "key");
        var first = catalog.resolve("p1", "model-a");
        save("https://gateway.example.test", "anthropic", "key");
        assertEquals(first.fingerprint(), catalog.resolve("p1", "model-a").fingerprint());
        save("https://user:private@gateway.example.test", "openai", "key");
        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("p1", "model-a"));
    }

    private void save(String endpoint, String protocol, String key) throws Exception {
        Files.writeString(file, """
                {"providers":[{"id":"p1","type":"custom","isEnabled":true,"baseUrl":"%s","protocol":"%s",
                  "apiKey":"%s","models":[{"id":"display-id","name":"展示名称","modelName":"model-a"}]}]}
                """.formatted(endpoint, protocol, key));
    }
}
