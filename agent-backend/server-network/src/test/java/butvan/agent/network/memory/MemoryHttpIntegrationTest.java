package butvan.agent.network.memory;

import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.memory.controller.MemoryController;
import butvan.agent.network.memory.service.MemoryService;
import butvan.agent.network.memory.service.MemoryStatusService;
import butvan.agent.network.reset.service.ResetOperationAspect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实 HTTP／AOP 适配验证代次、用途和幂等，拒绝靠配置开关冒充授权。 */
class MemoryHttpIntegrationTest {
    @TempDir Path directory;

    private MockMvc mvc(MemoryFixture f) {
        var proxy = new AspectJProxyFactory(f.service);
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new ResetOperationAspect(f.barrier));
        return MockMvcBuilders.standaloneSetup(new MemoryController(proxy.getProxy(), new MemoryStatusService(
                        f.service, new butvan.agent.network.memory.repository.MemoryStatusRepository(f.jdbc),
                        new butvan.agent.network.memory.repository.MemoryRecallRepository(f.jdbc), f.clock)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    private String grant() {
        return """
                {"expectedRevision":0,"recipientFingerprint":"fake-recipient-chain-v1","allowSave":true,
                 "allowRecall":false,"channels":["DESKTOP"],"retentionDays":30,"dailyWriteLimit":5,
                 "dailyQueryLimit":5,"confirmed":true,"requestId":"http-grant"}
                """;
    }

    @Test void generationAndGrantAreRequiredAndDeleteBodyRevokesIdempotently() throws Exception {
        var f = new MemoryFixture(directory);
        var mvc = mvc(f);
        mvc.perform(get("/agent/memory/settings")).andExpect(jsonPath("$.data.autoSave").value(false))
                .andExpect(jsonPath("$.data.authorized").value(false));
        mvc.perform(post("/agent/memory/authorization").contentType("application/json").content(grant()))
                .andExpect(jsonPath("$.code").value(428));
        mvc.perform(put("/agent/memory/settings").header("X-Agent-Generation", "0").contentType("application/json")
                .content("{\"expectedVersion\":0,\"autoSave\":true,\"autoRecall\":false}"))
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(post("/agent/memory/authorization").header("X-Agent-Generation", "0").contentType("application/json").content(grant()))
                .andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(put("/agent/memory/settings").header("X-Agent-Generation", "0").contentType("application/json")
                .content("{\"expectedVersion\":0,\"autoSave\":true,\"autoRecall\":true}"))
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(put("/agent/memory/settings").header("X-Agent-Generation", "0").contentType("application/json")
                .content("{\"expectedVersion\":0,\"autoSave\":true,\"autoRecall\":false}"))
                .andExpect(jsonPath("$.data.autoSave").value(true));
        var receipt = f.service.capture(f.source()).orElseThrow();
        for (int i = 0; i < 2; i++) mvc.perform(delete("/agent/memory/authorization").header("X-Agent-Generation", "0")
                        .contentType("application/json").content("{\"expectedRevision\":1,\"requestId\":\"http-revoke\"}"))
                .andExpect(jsonPath("$.data.revision").value(2));
        assertFalse(f.service.isCurrent(receipt));
        mvc.perform(get("/agent/memory/settings")).andExpect(jsonPath("$.data.authorized").value(false))
                .andExpect(jsonPath("$.data.autoSave").value(false));
        assertEquals(0, f.remote.deletions.get());
    }

    @Test void changedRecipientIsNotAuthorizedAndOldGenerationCannotWriteAfterReset() throws Exception {
        var f = new MemoryFixture(directory);
        var mvc = mvc(f);
        f.enabled();
        f.remote.fingerprint = "changed-chain";
        mvc.perform(get("/agent/memory/settings")).andExpect(jsonPath("$.data.authorized").value(false))
                .andExpect(jsonPath("$.data.recipientFingerprint").value("changed-chain"));
        f.barrier.begin("reset", "request", f.user.currentUserId());
        f.barrier.complete();
        mvc.perform(put("/agent/memory/settings").header("X-Agent-Generation", "0").contentType("application/json")
                .content("{\"expectedVersion\":1,\"autoSave\":false,\"autoRecall\":false}"))
                .andExpect(jsonPath("$.code").value(423));
    }
}
