package butvan.agent.network.memory;

import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.reset.AgentResetFiles;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.*;
import butvan.agent.agents.subagent.*;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.controller.MemoryDocumentsController;
import butvan.agent.network.memory.dto.MemoryDtos.Correct;
import butvan.agent.network.memory.dto.MemoryDtos.DeleteDocument;
import butvan.agent.network.memory.dto.MemoryTrialDtos.Connect;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.reset.MemoryResetParticipant;
import butvan.agent.network.memory.service.*;
import butvan.agent.network.reset.dto.ResetDtos.*;
import butvan.agent.network.reset.service.AgentResetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 真实试用 HTTP Adapter 的进程组件重建回归；仅本机假服务，不等同于真实飞书或安装包验收。 */
class MemoryTrialRecoveryIntegrationTest {
    @TempDir Path directory;

    @Test void restartKeepsAuthorizedChannelSourceAndCorrectionCannotBeRevived() throws Exception {
        try (var server = new MemoryTrialServer()) {
            String space, source, original = "老师说努力也跟不上时代，我只是转述，不是我的观点。";
            try (var runtime = new TrialRuntime()) {
                runtime.prepare(server, original, Channel.FEISHU);
                space = runtime.memory.settings().spaceId();
                source = runtime.management.documents("0", 20).items().getFirst().id();
            }
            String correctionId;
            try (var runtime = new TrialRuntime()) {
                assertEquals(3, server.reads.get(), "加载已有连接不重新探测远端");
                assertEquals(space, runtime.memory.settings().spaceId());
                assertEquals("FEISHU", runtime.management.document(source).document().channel());
                runtime.sync.runOnce(5);
                assertEquals(1, server.posts.get(), "已确认来源在重建组件后不重复上传");
                assertEquals(1, server.bankInitializations.get());
                for (var channel : List.of(Channel.DESKTOP, Channel.FEISHU)) {
                    var result = runtime.recall.recall(new MemoryRecallPort.Request(runtime.f.user.currentUserId(),
                            "之前老师的话是谁的观点？", 500, 4, channel, () -> false));
                    assertEquals(MemoryRecallPort.Status.SUCCESS, result.status());
                    assertTrue(result.fragments().getFirst().content().contains(original));
                }
                var document = runtime.management.document(source).document();
                var task = runtime.management.correct(source, new Correct(document.version(), "只是转述老师的话，不代表我的观点。", true, "correction"));
                correctionId = task.id();
                assertEquals("SUCCEEDED", task.state());
            }
            try (var runtime = new TrialRuntime()) {
                assertEquals(correctionId, runtime.management.document(source).document().operationId());
                assertEquals("SUCCEEDED", runtime.management.operation(correctionId).state());
                assertEquals(original, runtime.management.document(source).originalText());
                assertEquals("只是转述老师的话，不代表我的观点。", runtime.management.document(source).correctedText());
                var recalled = runtime.recall.recall(new MemoryRecallPort.Request(
                        runtime.f.user.currentUserId(), "之前关于努力的讨论", 500, 4, Channel.FEISHU, () -> false));
                assertEquals(MemoryRecallPort.Status.SUCCESS, recalled.status());
                assertTrue(recalled.fragments().getFirst().content().contains("只是转述老师的话，不代表我的观点。"));
                assertFalse(recalled.fragments().getFirst().content().contains(original));
                runtime.sync.runOnce(5);
                assertEquals(1, server.posts.get());
            }
        }
    }

    @Test void knownCleanupProgressIsReadableAfterReconstructionAndMarksDeletionSucceeded() throws Exception {
        try (var server = new MemoryTrialServer()) {
            String source, task;
            int requests;
            try (var runtime = new TrialRuntime()) {
                runtime.prepare(server, "合成来源：尝试用手记练习英语。", Channel.DESKTOP);
                var document = runtime.management.documents("0", 20).items().getFirst();
                source = document.id();
                assertFalse(document.cleanupProgress().documentDeletionRecorded());
                task = runtime.management.delete(source, new DeleteDocument(0, true, "progress-delete")).id();
                var result = runtime.management.retry(task);
                assertEquals("SUCCEEDED", result.state());
                var progress = result.cleanupProgress();
                assertTrue(progress.documentDeletionRecorded()); assertTrue(progress.factsClearanceRecorded());
                assertEquals(1, progress.knownChildTasks()); assertEquals(1, progress.childDeletionsRecorded());
                assertTrue(progress.parentDeletionRecorded());
                requests = server.requests.get();
                assertEquals(progress, runtime.management.document(source).document().cleanupProgress());
                assertEquals(progress, runtime.management.documents("0", 20).items().getFirst().cleanupProgress());
                var mvc = MockMvcBuilders.standaloneSetup(new MemoryDocumentsController(runtime.management)).build();
                mvc.perform(get("/agent/memory/operations/" + task))
                        .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                        .andExpect(jsonPath("$.data.cleanupProgress.documentDeletionRecorded").value(true))
                        .andExpect(jsonPath("$.data.cleanupProgress.childDeletionsRecorded").value(1));
                assertEquals(requests, server.requests.get(), "读取步骤不触发远端请求或重复删除");
            }
            try (var runtime = new TrialRuntime()) {
                var result = runtime.management.operation(task);
                assertEquals("SUCCEEDED", result.state()); assertTrue(result.cleanupProgress().parentDeletionRecorded());
                assertFalse(new MemoryRecallRepository(runtime.f.jdbc).deletionPending(runtime.memory.settings().spaceId()));
                assertEquals("REVOKED", runtime.management.document(source).document().state());
                assertEquals(requests, server.requests.get());
                assertEquals(1, server.posts.get());
                var other = runtime.f.service(runtime.remote, new CurrentUserProvider() {
                    @Override public String currentUserId() { return "other-user"; }
                }, "test");
                var foreign = new MemoryManagementService(other, new MemoryManagementRepository(runtime.f.jdbc), runtime.sources,
                        runtime.verifier, runtime.remote, runtime.f.barrier, runtime.f.transactions, runtime.clock);
                assertTrue(foreign.documents("0", 20).items().isEmpty());
                assertEquals(404, assertThrows(MemoryRequestException.class, () -> foreign.operation(task)).getCode());
                assertEquals(404, assertThrows(MemoryRequestException.class, () -> foreign.document(source)).getCode());
            }
        }
    }

    @Test void failedTaskCleanupReportsOnlyRecordedSteps() throws Exception {
        try (var server = new MemoryTrialServer(); var runtime = new TrialRuntime()) {
            runtime.prepare(server, "合成来源：周末尝试摄影。", Channel.DESKTOP);
            String source = runtime.management.documents("0", 20).items().getFirst().id();
            var task = runtime.management.delete(source, new DeleteDocument(0, true, "partial-delete"));
            server.rejectTaskCleanup = true;
            var result = runtime.management.retry(task.id());
            assertEquals("UNKNOWN", result.state());
            var progress = result.cleanupProgress();
            assertTrue(progress.documentDeletionRecorded()); assertTrue(progress.factsClearanceRecorded());
            assertEquals(1, progress.knownChildTasks()); assertEquals(0, progress.childDeletionsRecorded());
            assertFalse(progress.parentDeletionRecorded());
            assertEquals(progress, runtime.management.operation(task.id()).cleanupProgress());
            assertTrue(new MemoryRecallRepository(runtime.f.jdbc).deletionPending(runtime.memory.settings().spaceId()));
        }
    }

    @Test void httpErasureUnknownSurvivesResetRecoveryAndExplainsTheRealBlocker() throws Exception {
        try (var server = new MemoryTrialServer()) {
            String resetId;
            Path transcript;
            Path protectedFile = directory.resolve("data/protected-business.txt");
            try (var runtime = new TrialRuntime()) {
                runtime.prepare(server, "我还在尝试把手记翻译成英文，没有掌握英语。", Channel.DESKTOP);
                server.rejectTaskCleanup = true;
                transcript = runtime.f.storage.transcriptFile(runtime.session);
                Files.writeString(protectedFile, "业务数据必须保留");
                var reset = runtime.reset();
                try {
                    var preview = reset.preview();
                    reset.confirm(new Confirm(preview.confirmationToken(), AgentResetService.CONFIRMATION,
                            "reset-request", preview.scopeVersion()));
                    var failed = waitForFailure(reset);
                    resetId = failed.resetId();
                    assertEquals("UNKNOWN", failed.remoteStatus());
                    assertTrue(failed.message().contains("远端"), "不能把删除证据缺失误报成磁盘权限问题");
                    assertTrue(failed.message().contains("证据"));
                    assertTrue(runtime.f.barrier.blocked());
                } finally { reset.close(); }
            }
            try (var runtime = new TrialRuntime()) {
                var reset = runtime.reset();
                try {
                    assertTrue(runtime.f.barrier.blocked(), "重建后先恢复屏障，不能重新开放聊天");
                    reset.recover();
                    var failed = waitForFailure(reset);
                    assertEquals(resetId, failed.resetId());
                    assertEquals("UNKNOWN", failed.remoteStatus());
                    assertTrue(failed.message().contains("证据"));
                    assertTrue(runtime.f.barrier.blocked());
                    assertEquals(1, server.posts.get(), "重置恢复不重新提交正文");
                    assertTrue(Files.exists(transcript), "证据缺失时不能先丢掉本地来源与清理指针");
                    assertEquals("业务数据必须保留", Files.readString(protectedFile));
                } finally { reset.close(); }
            }
        }
    }

    private Status waitForFailure(AgentResetService reset) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!reset.current().status().equals("FAILED") && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("FAILED", reset.current().status());
        return reset.current();
    }

    /** 同一目录上重新构建所有试用组件，模拟应用重启，而不是替换成能返回 VERIFIED 的假删除。 */
    private final class TrialRuntime implements AutoCloseable {
        final MemorySyncFixture.AdjustableClock clock = new MemorySyncFixture.AdjustableClock();
        final MemoryFixture f = new MemoryFixture(directory, clock);
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final HindsightTrialService remote = new HindsightTrialService(new HindsightTrialStore(f.storage, mapper),
                new MemoryConnectionRepository(f.jdbc, f.transactions), new MemoryRemoteOperationRepository(f.jdbc, f.transactions), mapper, f.barrier);
        final MemoryService memory = f.service(remote, f.user, "test");
        final TranscriptService transcript = new TranscriptService(f.storage, mapper);
        final SessionCatalogService sessions = new SessionCatalogService(f.storage, f.user, mapper);
        final MemorySyncRepository sources = new MemorySyncRepository(f.jdbc);
        final MemorySourceVerifier verifier = new MemorySourceVerifier(transcript, sessions);
        final MemorySyncService sync = new MemorySyncService(memory, sources, transcript, sessions, f.user, f.barrier, remote, f.transactions, clock, "test");
        final MemoryManagementService management = new MemoryManagementService(memory, new MemoryManagementRepository(f.jdbc), sources,
                verifier, remote, f.barrier, f.transactions, clock);
        final MemoryRecallAdapter recall = new MemoryRecallAdapter(memory, remote, new MemoryRecallRepository(f.jdbc), sources, verifier,
                new PersonalContextService(f.storage, new ApproximateTokenCounter(), mapper), f.barrier, f.user, clock, f.transactions);
        String session;

        void prepare(MemoryTrialServer server, String original, Channel channel) {
            remote.connect(new Connect(0, server.endpoint(), "", false, "隔离恢复回归", true, false, "connect"));
            memory.authorize(new Authorization(0, remote.recipientFingerprint(), true, true, Set.of(Channel.DESKTOP, Channel.FEISHU),
                    7, 5, 30, true, "grant"));
            memory.saveSettings(0, true, true);
            session = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "隔离恢复场景")).id();
            var turns = new MemoryTurnAdapter(memory, sync, transcript, sessions, f.barrier);
            String turn = transcript.appendUserMessage(session, original);
            turns.admitted(new AgentUserCall(session, original, original, List.of(), UUID.randomUUID().toString(), null, List.of(), channel),
                    f.user.currentUserId(), turn);
            transcript.appendAssistantMessage(session, turn, "合成回复，不执行真实模型。", "", TranscriptMessageDto.MessageStatus.COMPLETED, 0L, List.of(), null);
            turns.completed(f.user.currentUserId(), session, turn, TranscriptMessageDto.MessageStatus.COMPLETED);
            sync.runOnce(5);
            clock.advance(Duration.ofSeconds(3));
            sync.runOnce(5);
            assertEquals("SUCCEEDED", sync.operations().getFirst().state());
        }

        AgentResetService reset() {
            var participant = new MemoryResetParticipant(new MemoryResetRepository(f.jdbc, f.repository), f.barrier, remote, f.transactions, "test");
            return new AgentResetService(f.barrier, new AgentResetFiles(f.storage), f.user, new PendingApprovalStore(), new ActiveAgentRunRegistry(),
                    new SubagentCatalog(new AgentDefinitionLoader(), f.storage), List.of(participant), clock);
        }
        public void close() { recall.close(); remote.close(); }
    }
}
