package butvan.agent.network.reset;

import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.reset.*;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.subagent.AgentDefinitionLoader;
import butvan.agent.agents.subagent.SubagentCatalog;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.reset.controller.AgentResetController;
import butvan.agent.network.reset.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实文件 Adapter 与真实 Spring AOP/HTTP 边界；不启用模型、渠道或用户目录。 */
class ResetHttpIntegrationTest {
    @TempDir Path directory;
    @RestController static class ProfileEndpoint {
        private final PersonalContextService profiles;
        ProfileEndpoint(PersonalContextService profiles) { this.profiles = profiles; }
        @PutMapping("/fixture/profile") Object save(@RequestBody Map<String, String> body) {
            return profiles.save("local-default", body.get("profile"));
        }
    }
    @Test void httpConfirmationAndGenerationFencePreventOldProfileFromReturning() throws Exception {
        var storage = new AgentStorageProperties(directory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var barrier = new ResetBarrier(storage);
        var factory = new AspectJProxyFactory(new PersonalContextService(storage, new ApproximateTokenCounter(), mapper));
        factory.addAspect(new ResetOperationAspect(barrier));
        PersonalContextService profiles = factory.getProxy();
        var service = new AgentResetService(barrier, new AgentResetFiles(storage), new CurrentUserProvider(),
                new PendingApprovalStore(), new ActiveAgentRunRegistry(),
                new SubagentCatalog(new AgentDefinitionLoader(), storage), List.of(), Clock.systemUTC());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentResetController(service), new ProfileEndpoint(profiles))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        try {
            mvc.perform(put("/fixture/profile").contentType("application/json").content("{\"profile\":\"旧画像\"}"))
                    .andExpect(jsonPath("$.code").value(428));
            mvc.perform(put("/fixture/profile").header("X-Agent-Generation", "0").contentType("application/json")
                    .content("{\"profile\":\"旧画像\"}")).andExpect(status().isOk());
            var preview = service.preview();
            String confirm = mapper.writeValueAsString(Map.of("confirmationToken", preview.confirmationToken(),
                    "scopeVersion", preview.scopeVersion(), "confirmationText", AgentResetService.CONFIRMATION,
                    "requestId", "http-reset"));
            mvc.perform(post("/agent/reset").contentType("application/json").content(confirm))
                    .andExpect(jsonPath("$.data.resetId").isString());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (barrier.blocked() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(barrier.blocked());
            mvc.perform(put("/fixture/profile").header("X-Agent-Generation", "0").contentType("application/json")
                    .content("{\"profile\":\"迟到旧画像\"}")).andExpect(jsonPath("$.code").value(423));
            assertEquals("", profiles.get("local-default").content());
            mvc.perform(get("/agent/reset/current")).andExpect(jsonPath("$.data.status").value("LOCAL_COMPLETE"))
                    .andExpect(jsonPath("$.data.remoteStatus").value("NOT_INTEGRATED"));
        } finally { service.close(); }
    }
}
