package butvan.agent.agents.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopControlPropertiesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsDisabledWhenConfigFileMissing(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        DesktopControlConfigData config = properties.load();
        assertFalse(config.enabled());
        assertEquals("", config.cliPath());
    }

    @Test
    void preservesSiblingConfigNodesAndExistingCliPath(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, """
                {
                  "activeVendor": "openai",
                  "feishu": { "enabled": true },
                  "typesafe": { "enabled": false }
                }
                """);

        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, configFile);
        DesktopControlConfigData updated = properties.updateConfig(true, "  /opt/homebrew/bin/agent-desktop  ");
        assertTrue(updated.enabled());
        assertEquals("/opt/homebrew/bin/agent-desktop", updated.cliPath());

        DesktopControlConfigData toggledOff = properties.updateConfig(false, null);
        assertFalse(toggledOff.enabled());
        assertEquals("/opt/homebrew/bin/agent-desktop", toggledOff.cliPath());

        JsonNode root = objectMapper.readTree(configFile.toFile());
        assertEquals("openai", root.path("activeVendor").asText());
        assertTrue(root.path("feishu").path("enabled").asBoolean());
        assertFalse(root.path("typesafe").path("enabled").asBoolean());
        assertFalse(root.path("desktopControl").path("enabled").asBoolean());
        assertEquals("/opt/homebrew/bin/agent-desktop", root.path("desktopControl").path("cliPath").asText());
    }

    @Test
    void rejectsInvalidCliPath(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        assertThrows(IllegalArgumentException.class, () -> properties.updateConfig(true, "agent-desktop\nrm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> properties.updateConfig(true, "a".repeat(600)));
    }
}
