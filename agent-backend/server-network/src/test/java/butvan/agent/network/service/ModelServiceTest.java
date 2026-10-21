package butvan.agent.network.service;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.agents.model.ModelFactory;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.usage.SystemUsageLedger;
import butvan.agent.agents.usage.UsagePurpose;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.dto.SetModel;
import butvan.agent.network.properties.ModelVendorProperties;
import butvan.agent.network.service.impl.ModelServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 SDK + 本地 HTTP 验证自定义协议、持久化与热切换，不访问用户配置或外部模型。 */
class ModelServiceTest {
    @TempDir Path home;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ModelServiceImpl service;
    private ModelHolder holder;
    private LocalConfigService config;
    private SystemUsageLedger ledger;
    private String requestPath;
    private JsonNode requestBody;
    private String authorization;
    private String apiKey;
    private String anthropicVersion;
    private String response;
    private int status = 200;

    @BeforeEach
    void setUp() throws Exception {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            config = new LocalConfigService();
        } finally {
            System.setProperty("user.home", original);
        }
        holder = new ModelHolder();
        ledger = new SystemUsageLedger(new AgentStorageProperties(home.resolve("usage-data")), mapper.findAndRegisterModules());
        service = new ModelServiceImpl(config, holder, new ModelVendorProperties(), ledger);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestBody = mapper.readTree(exchange.getRequestBody());
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
            anthropicVersion = exchange.getRequestHeaders().getFirst("anthropic-version");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contains("data:") ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private String response(String protocol) {
        return protocol.equals("openai") ? """
                {"id":"chat-test","object":"chat.completion","created":1,"model":"test-model",
                "choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}
                """ : """
                {"id":"msg_test","type":"message","role":"assistant","model":"test-model",
                "content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","stop_sequence":null,
                "usage":{"input_tokens":5,"output_tokens":1}}
                """;
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic"})
    void testsActualProtocolWithoutSavingOrSwitching(String protocol) {
        response = response(protocol);
        var result = service.testConnection(new SetModel("test-model", "custom", "test-key", url() + "/v1/", protocol));
        assertTrue(result.success(), result.message());
        assertEquals(protocol.equals("openai") ? "/v1/chat/completions" : "/v1/messages", requestPath);
        assertEquals("test-model", requestBody.get("model").asText());
        assertFalse(requestBody.path("stream").asBoolean());
        if (protocol.equals("openai")) assertEquals("Bearer test-key", authorization);
        else { assertEquals("test-key", apiKey); assertNotNull(anthropicVersion); }
        assertFalse(Files.exists(config.getConfigPath()));
        assertFalse(holder.isInitialized());
        assertEquals(1, ledger.list().size());
        assertEquals(UsagePurpose.MODEL_CONNECTION_TEST, ledger.list().getFirst().purpose());
        assertEquals(6, ledger.list().getFirst().usage().totalTokens());
    }

    @Test
    void persistsTwoIndependentCustomConnectionsAndRestoresSelectedEndpoint() throws Exception {
        var data = new LocalConfigService.ModelConfigData();
        for (String protocol : List.of("openai", "anthropic")) {
            var provider = new LocalConfigService.ProviderConfigData(protocol + "-connection", protocol, "custom", "test-key", true,
                    List.of(new LocalConfigService.ModelItemData("test-model", "显示名称", "test-model", protocol + "-connection", "", false)));
            provider.setBaseUrl(url() + "/v1");
            provider.setProtocol(protocol);
            data.getProviders().add(provider);
        }
        data.setVendor("anthropic-connection");
        data.setActiveVendor("anthropic-connection");
        data.setName("test-model");
        data.setActiveModel("test-model");
        data.setApiKey("stale-top-level-key");
        data.setExtraField("feishu", Map.of("enabled", true));
        service.saveFullModelConfig(data);
        var restored = config.loadFullConfigData();
        assertEquals(2, restored.getProviders().size());
        assertTrue(restored.getExtraFields().containsKey("feishu"));
        assertEquals("custom", restored.toSelector().vendor());
        assertEquals("anthropic", restored.toSelector().protocol());
        assertEquals("test-key", restored.toSelector().apiKey());
        assertEquals(url() + "/v1", restored.toSelector().baseUrl());
        response = response("anthropic");
        restored.setStream(false);
        var result = ModelFactory.create(restored.toSelector()).stream(List.of(UserMessage.builder().textContent("Hello").build()),
                List.of(), null).blockLast(Duration.ofSeconds(10));
        assertNotNull(result);
        assertNotNull(result.getUsage());
        assertEquals("/v1/messages", requestPath);
    }

    @Test
    void streamsOpenAiAndIncludesToolSchemasAndUsage() {
        response = """
                data: {"id":"chat-1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

                data: {"id":"chat-1","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}

                data: [DONE]

                """;
        var model = ModelFactory.create(new ModelSelector("custom", "test-model", "test-key", 0.7, true, url(), "openai"));
        var replies = model.stream(List.of(UserMessage.builder().textContent("Hello").build()),
                List.of(ToolSchema.builder().name("test_tool").description("test").build()), null)
                .collectList().block(Duration.ofSeconds(10));
        assertNotNull(replies);
        assertTrue(replies.stream().anyMatch(r -> r.getUsage() != null));
        assertTrue(requestBody.get("stream").asBoolean());
        assertEquals("test_tool", requestBody.at("/tools/0/function/name").asText());
    }

    @Test
    void streamsAnthropicThroughConfiguredGateway() {
        response = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant","model":"test-model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        var model = ModelFactory.create(new ModelSelector("custom", "test-model", "test-key", 0.7, true, url() + "/proxy/v1", "anthropic"));
        var replies = model.stream(List.of(UserMessage.builder().textContent("Hello").build()), List.of(), null)
                .collectList().block(Duration.ofSeconds(10));
        assertNotNull(replies);
        assertFalse(replies.isEmpty());
        assertTrue(replies.stream().flatMap(r -> r.getContent().stream())
                .anyMatch(block -> block instanceof io.agentscope.core.message.TextBlock text && "OK".equals(text.getText())));
        // AgentScope 2.0.0 会过滤 Anthropic 的纯 usage 事件；不得用本地估算伪造供应商实际值。
        assertTrue(replies.stream().allMatch(r -> r.getUsage() == null));
        assertTrue(requestBody.get("stream").asBoolean());
        assertEquals("/proxy/v1/messages", requestPath);
    }

    @Test
    void parsesAnthropicToolCalls() {
        response = """
                {"id":"msg_test","type":"message","role":"assistant","model":"test-model",
                "content":[{"type":"tool_use","id":"call-1","name":"test_tool","input":{}}],
                "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":1}}
                """;
        var model = ModelFactory.create(new ModelSelector("custom", "test-model", "test-key", 0.7, false, url(), "anthropic"));
        var reply = model.stream(List.of(UserMessage.builder().textContent("Hello").build()),
                List.of(ToolSchema.builder().name("test_tool").description("test").build()), null).blockLast(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertTrue(reply.getContent().stream().anyMatch(ToolUseBlock.class::isInstance));
        assertEquals("test_tool", requestBody.at("/tools/0/name").asText());
    }

    @Test
    void rejectsInvalidConfigurationBeforeSavingAndKeepsCurrentModel() {
        service.updateModelConfig(new SetModel("test-model", "custom", "test-key", url(), "openai"));
        ModelSelector previous = holder.getCurrentSelector();
        assertThrows(IllegalArgumentException.class, () -> service.updateModelConfig(
                new SetModel("bad", "custom", "test-key", "file:///tmp/model", "openai")));
        assertThrows(IllegalArgumentException.class, () -> service.updateModelConfig(
                new SetModel("bad", "custom", "test-key", url(), "invalid")));
        assertEquals(previous, holder.getCurrentSelector());
        assertEquals(previous, config.loadOrInitializeConfig());
    }

    @Test
    void providerErrorsAreNotSuccessfulAndDoNotLeakResponseBody() {
        response = "{\"error\":{\"message\":\"sensitive-test-key\",\"type\":\"invalid_request_error\"}}";
        status = 401;
        var result = service.testConnection(new SetModel("test-model", "custom", "test-key", url(), "openai"));
        assertFalse(result.success());
        assertFalse(result.message().contains("sensitive-test-key"));
    }

    @Test
    void deletingLastModelClearsPersistedAndRuntimeConfigurationButKeepsOtherSections() {
        service.updateModelConfig(new SetModel("test-model", "custom", "test-key", url(), "openai"));
        var existing = config.loadFullConfigData();
        existing.setExtraField("account", Map.of("enabled", true));
        config.saveFullConfigData(existing);
        var empty = new LocalConfigService.ModelConfigData();
        service.saveFullModelConfig(empty);
        assertFalse(holder.isInitialized());
        assertTrue(config.loadFullConfigData().getProviders().isEmpty());
        assertEquals("", config.loadOrInitializeConfig().vendor());
        assertTrue(config.loadFullConfigData().getExtraFields().containsKey("account"));
    }
}
