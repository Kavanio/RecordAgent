package butvan.agent.agents.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 config.json 中未知顶层节点（如 feishu）在模型配置读写后仍能原样保留。
 */
class ModelConfigRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void legacyConfigurationAndNewSelectorFieldsRoundTrip() throws Exception {
        var legacy = objectMapper.readValue("""
                {"vendor":"openai","name":"old-model","apiKey":"test-key","temperature":0.4,"stream":true}
                """, LocalConfigService.ModelConfigData.class);
        assertEquals("openai", legacy.toSelector().vendor());
        assertEquals("old-model", legacy.toSelector().name());
        assertEquals("", legacy.toSelector().baseUrl());
        var selector = new butvan.agent.agents.model.ModelSelector("custom", "model", "test-key", 0.7, true,
                "https://gateway.example/v1", "anthropic");
        var data = LocalConfigService.ModelConfigData.fromSelector(selector);
        assertEquals(selector, objectMapper.readValue(objectMapper.writeValueAsString(data),
                LocalConfigService.ModelConfigData.class).toSelector());
        assertNull(butvan.agent.agents.model.ModelSelector.of("openai", "model", "test-key").baseUrl());
    }

    @Test
    void preservesUnknownTopLevelSection() throws Exception {
        String json = """
                {
                  "activeVendor": "dashscope",
                  "feishu": {
                    "enabled": true,
                    "appId": "cli_test",
                    "appSecret": "secret"
                  }
                }
                """;

        LocalConfigService.ModelConfigData data =
                objectMapper.readValue(json, LocalConfigService.ModelConfigData.class);
        assertTrue(data.getExtraFields().containsKey("feishu"));

        String rewritten = objectMapper.writeValueAsString(data);
        assertTrue(rewritten.contains("\"feishu\""));
        assertTrue(rewritten.contains("cli_test"));
        // 未知节点应扁平写回，而不是被包成 extraFields 嵌套对象
        assertFalse(rewritten.contains("\"extraFields\""));
    }
}
