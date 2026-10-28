package butvan.agent.network.memory;

import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.*;
import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.repository.MemorySyncRepository;
import butvan.agent.network.memory.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** 真实临时 transcript／会话／SQLite 与假写入 Adapter，不创建模型或渠道。 */
final class MemorySyncFixture {
    final AdjustableClock clock = new AdjustableClock();
    final MemoryFixture base;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final TranscriptService transcript;
    final SessionCatalogService sessions;
    final String session;
    final FakeWriter writer;
    final MemorySyncService sync;
    final MemoryTurnAdapter adapter;
    MemorySyncFixture(Path directory) {
        base = new MemoryFixture(directory, clock);
        transcript = new TranscriptService(base.storage, mapper);
        sessions = new SessionCatalogService(base.storage, base.user, mapper);
        session = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "合成测试")).id();
        writer = new FakeWriter(base);
        sync = worker();
        adapter = new MemoryTurnAdapter(base.service, sync, transcript, sessions, base.barrier);
    }
    MemorySyncService worker() {
        return new MemorySyncService(base.service, new MemorySyncRepository(base.jdbc), transcript, sessions,
                base.user, base.barrier, writer, base.transactions, clock, "test");
    }
    AgentUserCall request(String text, Channel channel) {
        return new AgentUserCall(session, text, text, List.of(), "test-run", null, List.of(), channel);
    }
    String admit(String text) {
        String turn = transcript.appendUserMessage(session, text.strip());
        adapter.admitted(request(text, Channel.DESKTOP), base.user.currentUserId(), turn);
        return turn;
    }
    void finish(String turn, TranscriptMessageDto.MessageStatus status, boolean notify) {
        transcript.appendAssistantMessage(session, turn, "assistant-only-sentinel", "", status, 0L, List.of(), null);
        if (notify) adapter.completed(base.user.currentUserId(), session, turn, status);
    }
    static final class AdjustableClock extends Clock {
        final AtomicLong offset = new AtomicLong();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.now().plusMillis(offset.get()); }
        void advance(Duration duration) { offset.addAndGet(duration.toMillis()); }
    }
    static final class FakeWriter implements MemoryWriteGateway {
        final MemoryFixture base;
        final List<Write> writes = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        Outcome outcome = Outcome.SUCCEEDED;
        Outcome queryOutcome = Outcome.SUCCEEDED;
        Runnable beforeSubmit = () -> { };
        FakeWriter(MemoryFixture base) { this.base = base; }
        public String recipientFingerprint() { return base.remote.fingerprint; }
        public Outcome submit(Write request, BooleanSupplier cancelled) {
            if (cancelled.getAsBoolean()) return Outcome.FAILED;
            writes.add(request); beforeSubmit.run(); return outcome;
        }
        public Outcome query(String id, MemoryRemoteGateway.Target target, BooleanSupplier cancelled) {
            queries.add(id); return queryOutcome;
        }
    }
}
