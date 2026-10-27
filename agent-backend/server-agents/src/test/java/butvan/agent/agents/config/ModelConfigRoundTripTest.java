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
    void visionCapabilityFollowsSelectedModelAndDefaultsOff() throws Exception {
        String json = """
                {
                  "activeVendor": "custom-1",
                  "activeModel": "vision-model",
                  "providers": [{
                    "id": "custom-1", "name": "自定义", "type": "custom",
                    "baseUrl": "https://gateway.example/v1", "protocol": "openai", "apiKey": "test-key",
                    "models": [
                      {"id": "text-model", "name": "文本", "modelName": "text-model",
                       "providerId": "custom-1", "supportsVision": false},
                      {"id": "vision-model", "name": "视觉", "modelName": "vision-model",
                       "providerId": "custom-1", "supportsVision": true}
                    ]
                  }]
                }
                """;
        LocalConfigService.ModelConfigData data =
                objectMapper.readValue(json, LocalConfigService.ModelConfigData.class);
        assertTrue(data.toSelector().visionCapable());
        assertTrue(objectMapper.writeValueAsString(data).contains("\"supportsVision\" : true"));
        // 同一 Provider 下切到文本模型：能力必须跟着模型掉下来，而不是跟着连接。
        data.setActiveModel("text-model");
        assertFalse(data.toSelector().visionCapable());
    }

    @Test
    void missingVisionFieldDefaultsToTextOnly() throws Exception {
        String json = """
                {
                  "activeVendor": "dashscope", "activeModel": "qwen-plus",
                  "providers": [{"id": "dashscope", "type": "dashscope", "apiKey": "test-key",
                    "models": [{"id": "qwen-plus", "name": "qwen", "modelName": "qwen-plus"}]}]
                }
                """;
        LocalConfigService.ModelConfigData data =
                objectMapper.readValue(json, LocalConfigService.ModelConfigData.class);
        // 旧配置没有标注能力时必须按纯文本模型处理，不能假定能看图。
        assertFalse(data.toSelector().visionCapable());
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
