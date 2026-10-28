package butvan.agent.network.memory.client;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.memory.controller.MemoryConnectionController;
import butvan.agent.network.reset.service.ResetOperationAspect;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只使用本机随机端口假 HTTP；验证读合约、期限和默认关闭，不访问真实部署。 */
class HindsightReadinessServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> version = new AtomicReference<>("1.1.0");
    private final AtomicReference<String> ready = new AtomicReference<>("healthy");
    private final AtomicReference<String> contract = new AtomicReference<>(schema());
    private final AtomicInteger code = new AtomicInteger(200);
    private volatile Runnable beforeResponse = () -> { };

    @BeforeEach void startFake() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/gateway/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            tokens.add(Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst("Authorization"), ""));
            beforeResponse.run();
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/version") ? "{\"api_version\":\"" + version.get() + "\"}"
                    : path.endsWith("/ready") ? "{\"status\":\"" + ready.get() + "\"}" : contract.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (code.get() == 302) exchange.getResponseHeaders().set("Location", endpoint() + "redirected");
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code.get(), 0);
                exchange.getResponseBody().write(bytes);
            } catch (IOException cancelled) {
                // 超时／字节上限测试故意由客户端终止 exchange。
                assertFalse(paths.isEmpty());
            } finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stopFake() { server.stop(0); executor.shutdownNow(); }
    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway/"; }
    private ResetBarrier barrier() { return new ResetBarrier(new AgentStorageProperties(directory)); }
    private HindsightConnectionProperties properties() {
        var p = new HindsightConnectionProperties(); p.setProbeEnabled(true); p.setEndpoint(endpoint()); return p;
    }
    private HindsightReadinessService service(HindsightConnectionProperties p) { return new HindsightReadinessService(p, mapper, barrier()); }

    @Test void defaultAndDisabledConfigurationNeverMakesARequest() {
        var p = new HindsightConnectionProperties();
        try (var service = service(p)) {
            assertEquals("NOT_CONFIGURED", service.check().status());
            p.setEndpoint(endpoint()); assertEquals("NOT_CONFIGURED", service.check().status());
            assertTrue(paths.isEmpty());
        }
    }

    @Test void actualGetContractsAreBoundedAndCannotEnableDataOperations() {
        var p = properties(); p.setToken("synthetic-token");
        try (var service = service(p)) {
            var result = service.check();
            assertEquals("INSPECTED", result.status()); assertTrue(result.ready()); assertTrue(result.contractFieldsPresent());
            assertFalse(result.dataOperationsReady()); assertFalse(result.secureTransport()); assertTrue(result.credentialConfigured());
            assertEquals(List.of("GET /gateway/version", "GET /gateway/health/ready", "GET /gateway/openapi.json"), paths);
            assertTrue(tokens.stream().allMatch("Bearer synthetic-token"::equals));
            assertFalse(result.toString().contains("synthetic-token")); assertFalse(p.toString().contains("synthetic-token"));
        }
    }

    @Test void insecureRemoteAndUnsafeConfigurationFailBeforeAnyRequest() {
        var p = properties(); p.setToken("synthetic-secret");
        try (var service = service(p)) {
            p.setEndpoint("http://remote.example.invalid:18888/");
            assertEquals("INSECURE_TRANSPORT", service.check().status());
            for (String address : List.of("https://user:secret@example.invalid", "https://example.invalid/?token=secret",
                    "https://example.invalid/#fragment", "file:///tmp/secret", "//example.invalid", endpoint() + "../escape", endpoint() + "%2e%2e/escape")) {
                p.setEndpoint(address); assertEquals("INVALID_CONFIGURATION", service.check().status(), address);
            }
            p.setEndpoint(endpoint()); p.setToken("invalid\r\nAuthorization: injected");
            assertEquals("INVALID_CONFIGURATION", service.check().status()); assertTrue(paths.isEmpty());
        }
    }

    @Test void incompatibleVersionsUnreadyServiceAndMissingProtocolFieldsAreDistinct() {
        try (var service = service(properties())) {
            version.set("1.0.0"); assertEquals("VERSION_MISMATCH", service.check().status()); assertEquals(1, paths.size());
            version.set("1.1.0"); ready.set("starting"); assertEquals("NOT_READY", service.check().status());
            ready.set("healthy"); contract.set(schema().replace("operation_id", "unsupported_field"));
            assertEquals("CONTRACT_MISMATCH", service.check().status());
        }
    }

    @Test void redirectsMalformedJsonAndOversizedChunkedBodiesAreRejectedWithoutDisclosure() {
        try (var service = service(properties())) {
            code.set(302); assertEquals("UNAVAILABLE", service.check().status()); assertEquals(1, paths.size());
            code.set(200); contract.set("not-json synthetic-private-body");
            var malformed = service.check(); assertEquals("UNAVAILABLE", malformed.status());
            assertFalse(malformed.toString().contains("synthetic-private-body"));
            contract.set("{\"padding\":\"" + "x".repeat(600_000) + "\"}");
            assertEquals("UNAVAILABLE", service.check().status());
        }
    }

    @Test void overallDeadlineCancelsSlowExchangeAndConcurrentProbesDoNotMultiply() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeResponse = () -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        try (var service = new HindsightReadinessService(properties(), mapper, barrier(), Duration.ofMillis(250));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(service::check);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals("BUSY", service.check().status());
                assertEquals("UNAVAILABLE", first.get(2, TimeUnit.SECONDS).status());
                assertEquals(1, paths.size());
            } finally { release.countDown(); }
        }
    }

    @Test void resetAndInterruptCancelProbeWhileHoldingItsLeaseUntilExit() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        beforeResponse = () -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        var barrier = barrier();
        try (var service = new HindsightReadinessService(properties(), mapper, barrier);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(service::check);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(barrier.awaitIdle(Duration.ZERO));
                barrier.begin("reset", "request", "local-default");
                assertEquals("UNAVAILABLE", first.get(2, TimeUnit.SECONDS).status());
                assertTrue(barrier.awaitIdle(Duration.ZERO));
            } finally { release.countDown(); }
        }
        try (var service = new HindsightReadinessService(properties(), mapper, new ResetBarrier(new AgentStorageProperties(directory.resolve("interrupt"))))) {
            Thread.currentThread().interrupt();
            assertEquals("CANCELLED", service.check().status());
            assertTrue(Thread.interrupted());
        }
    }

    @Test void httpGenerationIsRequiredAndClientCannotChooseTheRemoteAddress() throws Exception {
        var p = properties(); p.setProbeEnabled(false); var barrier = barrier();
        try (var service = new HindsightReadinessService(p, mapper, barrier)) {
            var proxy = new AspectJProxyFactory(service); proxy.setProxyTargetClass(true); proxy.addAspect(new ResetOperationAspect(barrier));
            var mvc = MockMvcBuilders.standaloneSetup(new MemoryConnectionController(proxy.getProxy())).setControllerAdvice(new ApiExceptionHandler()).build();
            mvc.perform(post("/agent/memory/connection-test")).andExpect(jsonPath("$.code").value(428));
            mvc.perform(post("/agent/memory/connection-test").header("X-Agent-Generation", "0").contentType("application/json")
                    .content("{\"url\":\"http://example.invalid\",\"token\":\"attacker\"}"))
                    .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED")).andExpect(jsonPath("$.data.dataOperationsReady").value(false));
            assertTrue(paths.isEmpty());
        }
    }

    @Test void springConfigurationKeepsAllDataGatewaysDisabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.registerBean(ResetBarrier.class, this::barrier);
            context.register(MemoryRemoteConfiguration.class, HindsightReadinessService.class); context.refresh();
            assertEquals("NOT_CONFIGURED", context.getBean(HindsightReadinessService.class).check().status());
            assertTrue(context.getBean(MemoryRemoteGateway.class).recipientFingerprint().isEmpty());
            assertTrue(context.getBean(MemoryWriteGateway.class).recipientFingerprint().isEmpty());
            assertTrue(context.getBean(MemoryRecallGateway.class).recipientFingerprint().isEmpty());
            assertTrue(paths.isEmpty());
        }
    }

    private static String schema() {
        var mapper = new ObjectMapper(); var api = mapper.createObjectNode(); api.putObject("info").put("version", "1.1.0");
        var paths = api.putObject("paths"); String bank = "/v1/default/banks/{bank_id}";
        paths.putObject(bank + "/memories").putObject("post"); paths.putObject(bank + "/memories/recall").putObject("post");
        paths.putObject(bank + "/operations/{operation_id}").putObject("get");
        paths.putObject(bank + "/operations/{operation_id}/delete").putObject("delete");
        var documents = paths.putObject(bank + "/documents/{document_id}"); documents.putObject("get"); documents.putObject("delete");
        var schemas = api.putObject("components").putObject("schemas");
        Map<String, List<String>> fields = Map.of("RetainRequest", List.of("items", "async", "operation_id"),
                "RetainResponse", List.of("success", "bank_id", "async", "operation_id", "operation_ids"),
                "MemoryItem", List.of("content", "timestamp", "document_id", "update_mode"),
                "RecallRequest", List.of("query", "types", "max_tokens", "budget"),
                "RecallResult", List.of("text", "type", "document_id"),
                "OperationStatusResponse", List.of("operation_id", "status", "child_operations"));
        fields.forEach((name, names) -> { var properties = schemas.putObject(name).putObject("properties"); names.forEach(properties::putObject); });
        return api.toString();
    }
}
