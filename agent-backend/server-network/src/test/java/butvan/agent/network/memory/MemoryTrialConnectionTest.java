package butvan.agent.network.memory;

import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.dto.MemoryTrialDtos.Connect;
import butvan.agent.network.memory.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用户确认连接后可获得受信接收方，但连接本身不能授权、上传旧记录或开启开关。 */
class MemoryTrialConnectionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    HindsightTrialService runtime(MemoryFixture f) {
        return new HindsightTrialService(new HindsightTrialStore(f.storage, mapper), new MemoryConnectionRepository(f.jdbc, f.transactions),
                new MemoryRemoteOperationRepository(f.jdbc, f.transactions), mapper, f.barrier);
    }
    Connect input(String endpoint) { return new Connect(0, endpoint, "", false, "本机合约替身", true, false, "connect-1"); }

    @Test void missingRiskConfirmationAndUnsafeCredentialsNeverReachTheNetwork() throws Exception {
        var f = new MemoryFixture(directory);
        try (var server = new MemoryTrialServer(); var runtime = runtime(f)) {
            assertThrows(butvan.agent.network.memory.service.MemoryRequestException.class, () -> runtime.connect(
                    new Connect(0, server.endpoint(), "", false, "chain", false, false, "no-consent")));
            for (String endpoint : java.util.List.of("http://public.example.invalid/", "http://user:private-key@public.example.invalid/", "https://example.invalid/?token=private-key")) {
                var error = assertThrows(butvan.agent.network.memory.service.MemoryRequestException.class, () -> runtime.connect(
                        new Connect(0, endpoint, "private-key", false, "chain", true, true, "unsafe")));
                assertFalse(error.getMessage().contains("private-key"));
            }
            assertThrows(IllegalArgumentException.class, () -> new HindsightHttpAdapter(java.net.URI.create("http://public.example.invalid/"), "", "chain", null, mapper));
            assertEquals(0, server.reads.get()); assertFalse(runtime.connection().configured());
        }
    }

    @Test void wrongVersionDoesNotCreateAnActiveConnectionOrBank() throws Exception {
        var f = new MemoryFixture(directory);
        try (var server = new MemoryTrialServer(); var runtime = runtime(f)) {
            server.version = "9.0.0";
            assertThrows(butvan.agent.network.memory.service.MemoryRequestException.class, () -> runtime.connect(input(server.endpoint())));
            assertFalse(runtime.connection().configured()); assertFalse(Files.exists(f.storage.getHindsightConnectionFile()));
            assertEquals(1, server.reads.get()); assertEquals(0, server.bankInitializations.get()); assertEquals(0, server.posts.get());
        }
    }

    @Test void httpGenerationIsRequiredAndCredentialsAreNeverInTheResponse() throws Exception {
        var f = new MemoryFixture(directory);
        try (var server = new MemoryTrialServer(); var runtime = runtime(f)) {
            var proxy = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(runtime);
            proxy.setProxyTargetClass(true); proxy.addAspect(new butvan.agent.network.reset.service.ResetOperationAspect(f.barrier));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new butvan.agent.network.memory.controller.MemoryTrialConnectionController(proxy.getProxy()))
                    .setControllerAdvice(new butvan.agent.network.controller.ApiExceptionHandler()).build();
            var input = new Connect(0, server.endpoint(), "secret-sentinel", false, "chain", true, false, "http-connect");
            String body = mapper.writeValueAsString(input);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/agent/memory/trial-connection")
                    .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(428));
            assertEquals(0, server.reads.get());
            String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/agent/memory/trial-connection")
                    .header("X-Agent-Generation", "0").contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.configured").value(true))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.credentialConfigured").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("secret-sentinel")); assertFalse(input.toString().contains("secret-sentinel"));
            assertFalse(new HindsightTrialStore(f.storage, mapper).read().orElseThrow().toString().contains("secret-sentinel"));
            f.barrier.begin("reset", "request", f.user.currentUserId());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/agent/memory/trial-connection")
                    .header("X-Agent-Generation", "0").contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(423));
            assertEquals(3, server.reads.get());
        }
    }

    @Test void springUsesTheSameDefaultClosedTrialRuntimeForAllThreeGatewayRoles() {
        var f = new MemoryFixture(directory);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(HindsightTrialService.class, () -> runtime(f));
            context.register(MemoryRemoteConfiguration.class); context.refresh();
            var runtime = context.getBean(HindsightTrialService.class);
            assertSame(runtime, context.getBean(MemoryWriteGateway.class)); assertSame(runtime, context.getBean(MemoryRecallGateway.class));
            assertSame(runtime, context.getBean(MemoryRemoteGateway.class)); assertEquals("", runtime.recipientFingerprint());
        }
    }

    @Test void bankIdentityMustMatchAndAnUnknownOriginalRequestIsNeverResent() throws Exception {
        var f = new MemoryFixture(directory);
        try (var server = new MemoryTrialServer(); var runtime = runtime(f)) {
            runtime.connect(input(server.endpoint()));
            var memory = f.service(runtime, f.user, "test");
            memory.authorize(new butvan.agent.network.memory.model.MemoryModels.Authorization(0, runtime.recipientFingerprint(), true, false,
                    java.util.Set.of(butvan.agent.agents.memory.MemorySourceRegistry.Channel.DESKTOP), 7, 5, 20, true, "grant"));
            memory.saveSettings(0, true, false);
            var receipt = memory.capture(f.source()).orElseThrow(); String id = java.util.UUID.randomUUID().toString();
            memory.recordRemoteOperation(receipt, id);
            var row = new MemorySyncRepository(f.jdbc).source(receipt.sourceId()).orElseThrow();
            var target = new MemoryRemoteGateway.Target(row.space(), row.document(), runtime.recipientFingerprint(), id);
            var write = new MemoryWriteGateway.Write(id, target, "合成协议样本，不能在空间身份不符时发送。", row.occurredAt(), row.channel());
            server.rejectBank = true;
            assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, runtime.submit(write, () -> false));
            assertEquals(1, server.bankInitializations.get()); assertEquals(0, server.posts.get());
            server.rejectBank = false;
            assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, runtime.submit(write, () -> false));
            assertEquals(1, server.bankInitializations.get()); assertEquals(0, server.posts.get());
        }
    }

    @Test void corruptOrUnconfirmedConfigurationStaysClosedAndIsNotOverwritten() throws Exception {
        var f = new MemoryFixture(directory);
        Files.writeString(f.storage.getHindsightConnectionFile(), "{invalid-private-configuration");
        try (var runtime = runtime(f)) {
            assertFalse(runtime.connection().configured());
            assertThrows(butvan.agent.network.memory.service.MemoryRequestException.class, () -> runtime.connect(input("https://unused.invalid/")));
            assertEquals("{invalid-private-configuration", Files.readString(f.storage.getHindsightConnectionFile()));
        }
        var unconfirmed = new HindsightTrialStore.Configuration(1, "https://unused.invalid/", "", "chain", false, false, "manual");
        new HindsightTrialStore(f.storage, mapper).write(unconfirmed);
        try (var runtime = runtime(f)) {
            assertFalse(runtime.connection().configured()); assertEquals("", runtime.recipientFingerprint());
        }
    }

    @Test void explicitConnectionChecksContractAndSurvivesRestartWithoutGrantOrNetworkOnLoad() throws Exception {
        var f = new MemoryFixture(directory);
        try (var server = new MemoryTrialServer()) {
            String fingerprint;
            try (var runtime = runtime(f)) {
                assertFalse(runtime.connection().configured()); assertEquals(0, server.reads.get());
                var result = runtime.connect(input(server.endpoint()));
                assertTrue(result.configured()); assertEquals(1, result.version()); fingerprint = result.recipientFingerprint();
                assertFalse(fingerprint.isBlank()); assertEquals(3, server.reads.get()); assertEquals(0, server.posts.get());
                var memory = f.service(runtime, f.user, "test");
                assertTrue(memory.remoteAvailable()); assertFalse(memory.state().authorized());
                assertFalse(memory.settings().autoSave()); assertFalse(memory.settings().autoRecall());
                assertEquals(result, runtime.connect(input(server.endpoint())), "同一请求重试不重复探测或增加版本");
            }
            try (var restored = runtime(f)) {
                assertTrue(restored.connection().configured()); assertEquals(fingerprint, restored.recipientFingerprint());
                assertEquals(3, server.reads.get()); assertEquals(0, server.posts.get());
            }
            if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.storage.getHindsightConnectionFile())));
        }
    }
}
