package butvan.agent.network.memory;

import butvan.agent.agents.context.*;
import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.agents.usage.*;
import butvan.agent.network.memory.client.MemoryRecallGateway;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.service.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.ModelCallInput;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

/** 真实授权／来源／信封及 Middleware 配合假召回，模型输入仅捕获于内存。 */
final class MemoryRecallFixture implements AutoCloseable {
    final MemorySyncFixture sync;
    final ApproximateTokenCounter counter = new ApproximateTokenCounter();
    final PersonalContextService profiles;
    final FakeRecall gateway;
    final MemoryRecallAdapter adapter;
    final ConversationContextAssembler assembler;
    final ContextInjectionMiddleware middleware = new ContextInjectionMiddleware();

    MemoryRecallFixture(Path path) { this(path, Duration.ofMillis(1500)); }
    MemoryRecallFixture(Path path, Duration timeout) {
        sync = new MemorySyncFixture(path);
        profiles = new PersonalContextService(sync.base.storage, counter, sync.mapper);
        gateway = new FakeRecall(sync.base);
        adapter = new MemoryRecallAdapter(sync.base.service, gateway, new MemoryRecallRepository(sync.base.jdbc),
                new MemorySyncRepository(sync.base.jdbc), new MemorySourceVerifier(sync.transcript, sync.sessions), profiles,
                sync.base.barrier, sync.base.user, sync.clock, sync.base.transactions, timeout);
        assembler = new ConversationContextAssembler(sync.base.storage, counter, profiles);
        assembler.setResetBarrier(sync.base.barrier);
        assembler.setRemoteRecall(adapter);
        middleware.setTokenCounter(counter);
    }
    void prepared() { prepared(5); }
    void prepared(int queryLimit) {
        sync.base.service.authorize(new butvan.agent.network.memory.model.MemoryModels.Authorization(0, sync.base.remote.fingerprint,
                true, true, Set.of(Channel.DESKTOP), 30, 5, queryLimit, true, "grant"));
        sync.base.service.saveSettings(0, true, true);
        sync.finish(sync.admit("今天尝试把手记翻成英文，感觉不错。"), MessageStatus.COMPLETED, true);
        sync.sync.runOnce(20);
        gateway.reply = List.of(new MemoryRecallGateway.Candidate("曾尝试用手记学英语，当时感觉不错。",
                List.of(sync.writer.writes.getFirst().target().documentId()), true));
    }
    ContextRequest request(String query) { return new ContextRequest("local-default", query, Channel.DESKTOP, () -> false); }
    MemoryRecallPort.Request portRequest(java.util.function.BooleanSupplier cancelled) {
        return new MemoryRecallPort.Request("local-default", "英语学习", 500, 4, Channel.DESKTOP, cancelled);
    }
    RuntimeContext context(ContextEnvelope envelope) {
        return RuntimeContext.builder().put(ContextEnvelope.class, envelope)
                .put(TokenUsageRoundContext.class, new TokenUsageRoundContext("current-turn")).build();
    }
    List<Msg> inject(RuntimeContext context) {
        var user = UserMessage.builder().textContent("当前问题")
                .metadata(Map.of(TokenUsageRoundContext.TURN_METADATA_KEY, "current-turn")).build();
        var input = new ModelCallInput(List.of(new SystemMessage("system"), user), List.of(), null, null);
        var captured = new AtomicReference<ModelCallInput>();
        middleware.onModelCall(null, context, input, value -> { captured.set(value); return Flux.empty(); }).blockLast();
        org.junit.jupiter.api.Assertions.assertEquals(2, input.messages().size());
        return captured.get().messages();
    }
    public void close() { adapter.close(); }
    static final class FakeRecall implements MemoryRecallGateway {
        final MemoryFixture base;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Query> last = new AtomicReference<>();
        volatile List<Candidate> reply = List.of();
        volatile BiFunction<Query, Control, List<Candidate>> action;
        FakeRecall(MemoryFixture base) { this.base = base; action = (q,c) -> reply; }
        public String recipientFingerprint() { return base.remote.fingerprint; }
        public List<Candidate> recall(Query query, Control control) {
            calls.incrementAndGet(); last.set(query); return action.apply(query, control);
        }
    }
}
