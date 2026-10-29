package butvan.agent.network.memory;

import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.context.*;
import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.*;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.dto.MemoryDtos.DeleteDocument;
import butvan.agent.network.memory.dto.MemoryTrialDtos.Connect;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 正式试用 Adapter 配合真实后台消费、来源、跨会话信封与管理；上游是假 HTTP，不读取真实数据。 */
class MemoryTrialChatIntegrationTest {
    @TempDir Path directory;
    @Test void onlyNewAuthorizedChatIsAutomaticallyStoredAndRecalledThenWithdrawalInvalidatesIt() throws Exception {
        var clock = new MemorySyncFixture.AdjustableClock(); var f = new MemoryFixture(directory, clock);
        var mapper = new ObjectMapper().findAndRegisterModules();
        try (var server = new MemoryTrialServer();
             var remote = new HindsightTrialService(new HindsightTrialStore(f.storage, mapper), new MemoryConnectionRepository(f.jdbc, f.transactions),
                     new MemoryRemoteOperationRepository(f.jdbc, f.transactions), mapper, f.barrier)) {
            remote.connect(new Connect(0, server.endpoint(), "", false, "合成试用替身", true, false, "connect"));
            var memory = f.service(remote, f.user, "test");
            var transcript = new TranscriptService(f.storage, mapper); var sessions = new SessionCatalogService(f.storage, f.user, mapper);
            String session = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "合成来源会话")).id();
            var sourceRows = new MemorySyncRepository(f.jdbc);
            var sync = new MemorySyncService(memory, sourceRows, transcript, sessions, f.user, f.barrier, remote, f.transactions, clock, "test");
            var turns = new MemoryTurnAdapter(memory, sync, transcript, sessions, f.barrier);
            append(turns, transcript, session, "这条授权前的旧聊天不能被补传。", f.user.currentUserId());
            memory.authorize(new Authorization(0, remote.recipientFingerprint(), true, true, Set.of(Channel.DESKTOP), 7, 5, 30, true, "grant"));
            memory.saveSettings(0, true, true);
            String content = "我尝试把每日手记翻成英文练习英语，目前还在摸索，没有掌握。";
            append(turns, transcript, session, content, f.user.currentUserId());
            try (var scheduler = new MemorySyncScheduler(sync, remote, Duration.ofMillis(10))) {
                await(() -> sync.operations().size() == 1 && sync.operations().getFirst().state().equals("SUCCEEDED"));
                assertEquals(1, server.posts.get()); assertEquals(1, server.bankInitializations.get());
                assertEquals(content, server.writes.values().iterator().next().path("items").get(0).path("content").asText());
                String next = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "合成新会话")).id();
                assertNotEquals(session, next);
                var counter = new ApproximateTokenCounter(); var profiles = new PersonalContextService(f.storage, counter, mapper);
                var verifier = new MemorySourceVerifier(transcript, sessions);
                var recall = new MemoryRecallAdapter(memory, remote, new MemoryRecallRepository(f.jdbc), sourceRows, verifier, profiles,
                        f.barrier, f.user, clock, f.transactions);
                try {
                    var assembler = new ConversationContextAssembler(f.storage, counter, profiles);
                    assembler.setResetBarrier(f.barrier); assembler.setRemoteRecall(recall);
                    var query = new ContextRequest(f.user.currentUserId(), "我之前尝试过什么英语学习方法？", Channel.DESKTOP, () -> false);
                    var envelope = assembler.assemble(query);
                    assertTrue(envelope.rendered().contains("手记")); assertTrue(envelope.memoryTokens() <= 500);
                    assertEquals(1, server.recalls.get());
                    var management = new MemoryManagementService(memory, new MemoryManagementRepository(f.jdbc), sourceRows, verifier,
                            remote, f.barrier, f.transactions, clock);
                    var document = management.documents("0", 20).items().getFirst();
                    var deletion = management.delete(document.id(), new DeleteDocument(document.version(), true, "delete"));
                    var paused = assembler.assemble(query);
                    assertEquals(0, paused.memoryTokens());
                    assertTrue(paused.rendered().contains("删除未核实"));
                    assertFalse(paused.rendered().contains(content), "只说明暂停，不泄漏已撤回的个人经历");
                    assertEquals(1, server.recalls.get(), "撤回后不再发送查询");
                    assertTrue(envelope.blocks().stream().filter(block -> block.kind() == ContextKind.MEMORY)
                            .noneMatch(block -> block.stillCurrent().getAsBoolean()));
                    assertEquals("UNKNOWN", management.retry(deletion.id()).state(), "已知清理成功也不冒充完整擦除");
                    assertEquals(content, management.document(document.id()).originalText(), "保留原聊天");
                    assertEquals(1, server.posts.get(), "撤回及后台消费都不能重发旧正文");
                    assertThrows(MemoryRequestException.class, () -> remote.connect(new Connect(1, server.endpoint(), "", false,
                            "改变处理链", true, false, "other-config")), "未核实目标存在时保留旧接收方与凭据");
                } finally { recall.close(); }
            }
        }
    }
    private void append(MemoryTurnAdapter turns, TranscriptService transcript, String session, String content, String owner) {
        String turn = transcript.appendUserMessage(session, content);
        turns.admitted(new AgentUserCall(session, content, content, List.of(), UUID.randomUUID().toString(), null, List.of(), Channel.DESKTOP), owner, turn);
        transcript.appendAssistantMessage(session, turn, "合成回复", "", TranscriptMessageDto.MessageStatus.COMPLETED, 0L, List.of(), null);
        turns.completed(owner, session, turn, TranscriptMessageDto.MessageStatus.COMPLETED);
    }
    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "新聊天应由正式后台消费者处理，不要求用户手工调用 Worker");
    }
}
