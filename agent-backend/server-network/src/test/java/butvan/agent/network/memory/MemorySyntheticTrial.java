package butvan.agent.network.memory;

import butvan.agent.agents.context.PersonalContextService;
import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.agents.usage.ApproximateTokenCounter;
import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.dto.MemoryDtos.*;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** 仅供显式试跑的测试代码：独立本地账本、三个固定虚构来源，不装配生产 Bean。 */
final class MemorySyntheticTrial implements AutoCloseable {
    enum Action { PREPARE, CHECK, SYNC, RECALL, WITHDRAW, STATUS }
    record Task(String id, String sourceId, String state, int attempts, int queries) { }
    record Match(String source, String content) { }
    record Report(String action, String status, String bankId, boolean remoteErasureVerified, int recallUsed,
                  List<Task> operations, List<Match> matches, List<OperationResponse> deletions, boolean explicitPlaintextTrial,
                  List<ApprovedSyntheticHindsight.Observation> transportObservations,
                  int policyVersion, int simulatedSourceAgeDays) { }
    private static final String SCHEMA = "recordagent-synthetic-trial-v1";
    private static final List<String> SAMPLES = SyntheticMemoryTrialData.SAMPLES;
    private static final List<String> QUERIES = SyntheticMemoryTrialData.QUERIES;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path root;
    private final HindsightConnectionProperties connection = new HindsightConnectionProperties();
    private FileChannel channel;
    private FileLock lock;
    private HindsightHttpAdapter gateway;
    private ApprovedSyntheticHindsight.Session approvedHttp;
    private MemorySyncFixture fixture;
    private MemorySyncService worker;
    private MemoryManagementService management;
    private ObjectNode state;
    /** 只改变本地合成授权与召回核对时钟，不改变远端正文或调用白名单。 */
    private final boolean v2;
    private boolean clockAdvanced;

    MemorySyntheticTrial(Path directory, URI endpoint, String token, String chain) throws Exception {
        this(directory, endpoint, token, chain, false);
    }
    MemorySyntheticTrial(Path directory, URI endpoint, String token, String chain, boolean explicitPlaintext) throws Exception {
        this(directory, endpoint, token, chain, explicitPlaintext, false);
    }
    MemorySyntheticTrial(Path directory, URI endpoint, String token, String chain,
                         boolean explicitPlaintext, boolean v2) throws Exception {
        this.v2 = v2;
        root = directory.toAbsolutePath().normalize();
        try {
            if (!root.getFileName().toString().startsWith("recordagent-synthetic-trial-") || Files.isSymbolicLink(root))
                throw new IllegalArgumentException("只能使用单独命名的合成试跑目录");
            boolean existing = Files.exists(root);
            if (existing && (!Files.isRegularFile(root.resolve("trial.json"), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(root.resolve("trial.json")) > 65536))
                throw new IllegalArgumentException("目录没有合成试跑标记，拒绝读取或复用");
            if (!existing) Files.createDirectory(root);
            channel = FileChannel.open(root.resolve("trial.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IllegalStateException("已有试跑正在使用此目录"); }
            if (lock == null) throw new IllegalStateException("已有试跑正在使用此目录");
            if (existing) {
                var saved = mapper.readTree(root.resolve("trial.json").toFile());
                if (!saved.isObject() || !SCHEMA.equals(saved.path("schema").asText()))
                    throw new IllegalArgumentException("不支持的试跑记录，拒绝复用");
                state = (ObjectNode) saved;
            } else state = mapper.createObjectNode().put("schema", SCHEMA);
            if (existing && state.path("v2").asBoolean() != v2)
                throw new IllegalArgumentException("不能改变已有合成空间的授权模式");
            state.put("v2", v2);
            fixture = new MemorySyncFixture(root);
            var journal = new MemoryRemoteOperationRepository(fixture.base.jdbc, fixture.base.transactions);
            if (explicitPlaintext) {
                approvedHttp = ApprovedSyntheticHindsight.open(endpoint, token, chain, journal, mapper);
                gateway = approvedHttp.adapter();
            } else gateway = new HindsightHttpAdapter(endpoint, token, chain, journal, mapper);
            String fingerprint = gateway.recipientFingerprint();
            if (existing && !fingerprint.equals(state.path("recipient").asText()))
                throw new IllegalArgumentException("连接或处理链已变化，请先处理原试跑目标");
            state.put("recipient", fingerprint).put("explicitPlaintextTrial", approvedHttp != null);
            fixture.base.remote.fingerprint = fingerprint;
            var memory = fixture.base.service(gateway, fixture.base.user, "test");
            worker = new MemorySyncService(memory, new MemorySyncRepository(fixture.base.jdbc), fixture.transcript,
                    fixture.sessions, fixture.base.user, fixture.base.barrier, gateway, fixture.base.transactions, fixture.clock, "test");
            management = new MemoryManagementService(memory, new MemoryManagementRepository(fixture.base.jdbc),
                    new MemorySyncRepository(fixture.base.jdbc), new MemorySourceVerifier(fixture.transcript, fixture.sessions),
                    gateway, fixture.base.barrier, fixture.base.transactions, fixture.clock);
            String bank = "ra-" + memory.settings().spaceId();
            if (existing && !bank.equals(state.path("bank").asText())) throw new IllegalArgumentException("试跑空间记录不一致");
            state.put("bank", bank);
            connection.setProbeEnabled(true); connection.setEndpoint(endpoint.toString()); connection.setToken(token);
            save("trial.json", state);
        } catch (Exception failure) { close(); throw failure; }
    }

    /** 每次只执行一个显式步骤；没有后台线程轮询，也不能接收自由文本或指定已有 bank。 */
    Report run(Action action) throws Exception {
        String status = "LOCAL_STATUS";
        List<Match> matches = List.of();
        List<OperationResponse> deletions = new ArrayList<>();
        if (action == Action.PREPARE) {
            if (!state.path("prepared").asBoolean()) {
                if (state.path("seedStarted").asBoolean()) throw new IllegalStateException("本地准备曾中断，请保留目录检查，不能重新生成来源");
                state.put("seedStarted", true); save("trial.json", state);
                fixture.base.service.authorize(v2
                        ? new Authorization(0, gateway.recipientFingerprint(), true, true, Set.of(Channel.DESKTOP),
                                0, 0, 0, 2, butvan.agent.network.memory.model.MemoryModels.RecallMode.ALL_AUTHORIZED,
                                true, true, true, "synthetic-v2-grant")
                        : new Authorization(0, gateway.recipientFingerprint(), true, true,
                                Set.of(Channel.DESKTOP), 1, 3, 30, true, "synthetic-grant"));
                fixture.base.service.saveSettings(0, true, true);
                for (String text : SAMPLES) fixture.finish(fixture.admit(text), MessageStatus.COMPLETED, true);
                var ids = state.putArray("sources"); worker.operations().forEach(operation -> ids.add(operation.sourceId()));
                state.put("prepared", true); save("trial.json", state);
            }
            verifySources();
            if (approvedHttp != null && !state.path("bankReady").asBoolean()) {
                String bank = approvedHttp.initializeBank();
                if (!bank.equals("READY")) throw new IllegalStateException("合成试跑空间尚未就绪：" + bank);
                state.put("bankReady", true); save("trial.json", state);
            }
            status = "PREPARED";
        } else if (action == Action.CHECK) status = check();
        else if (action == Action.WITHDRAW) {
            verifyIdentities();
            if (state.path("withdrawUsed").asInt() >= 3) status = "TRIAL_WITHDRAW_LIMIT";
            else {
                state.put("withdrawUsed", state.path("withdrawUsed").asInt() + 1); save("trial.json", state);
                // 先提交全部本地停用，再探测和核对远端；服务断连不能阻止撤回。
                for (var task : worker.operations()) {
                    var command = state.withObject("/deletions").withObject("/" + task.sourceId());
                    if (!command.has("version")) {
                        command.put("version", management.document(task.sourceId()).document().version()); save("trial.json", state);
                    }
                    var operation = management.delete(task.sourceId(), new DeleteDocument(command.path("version").asLong(),
                            true, "synthetic-delete-" + task.sourceId()));
                    command.put("operation", operation.id()); save("trial.json", state); deletions.add(operation);
                }
                if (deletions.stream().anyMatch(operation -> !operation.state().equals("SUCCEEDED")) && check().equals("INSPECTED"))
                    deletions = deletions.stream().map(operation -> management.retry(operation.id())).toList();
                status = "WITHDRAWN_REMOTE_UNVERIFIED";
            }
        } else if (action != Action.STATUS) {
            verifySources();
            // STATUS／本地准备不会探测；数据步骤每次先核对版本与必要字段。
            status = check();
            if (status.equals("INSPECTED")) {
                switch (action) {
                    case SYNC -> { worker.runOnce(3); status = "SYNC_CHECKED"; }
                    case RECALL -> {
                        if (worker.operations().stream().anyMatch(operation -> !operation.state().equals("SUCCEEDED"))) status = "NOT_SYNCED";
                        else {
                            int index = state.path("recallUsed").asInt();
                            if (index >= QUERIES.size()) status = "TRIAL_QUERY_LIMIT";
                            else {
                                state.put("recallUsed", index + 1); save("trial.json", state);
                                var profiles = new PersonalContextService(fixture.base.storage, new ApproximateTokenCounter(), mapper);
                                var recall = new MemoryRecallAdapter(fixture.base.service, gateway,
                                        new MemoryRecallRepository(fixture.base.jdbc), new MemorySyncRepository(fixture.base.jdbc),
                                        new MemorySourceVerifier(fixture.transcript, fixture.sessions), profiles,
                                        fixture.base.barrier, fixture.base.user, fixture.clock, fixture.base.transactions);
                                try {
                                    // 仅推进独立客户端时钟；远端不伪造时间，检验V2客户端长时资格。
                                    if (v2 && !clockAdvanced) {
                                        fixture.clock.advance(java.time.Duration.ofDays(400));
                                        clockAdvanced = true;
                                    }
                                    var result = recall.recall(new MemoryRecallPort.Request(fixture.base.user.currentUserId(),
                                            QUERIES.get(index), 500, 4, Channel.DESKTOP, () -> false));
                                    status = result.status().name();
                                    matches = result.fragments().stream().filter(fragment -> fragment.stillCurrent().getAsBoolean())
                                            .map(fragment -> new Match(fragment.source(), fragment.content())).toList();
                                } finally { recall.close(); }
                            }
                        }
                    }
                    default -> throw new IllegalStateException("不支持的试跑步骤");
                }
            }
        }
        var tasks = worker.operations().stream().map(operation -> new Task(operation.id(), operation.sourceId(),
                operation.state(), operation.attempts(), operation.queries())).toList();
        var report = new Report(action.name(), status, state.path("bank").asText(), false, state.path("recallUsed").asInt(), tasks, matches, deletions, approvedHttp != null,
                approvedHttp == null ? List.of() : approvedHttp.observations(), v2 ? 2 : 1,
                clockAdvanced ? 400 : 0);
        save("latest-report.json", mapper.valueToTree(report));
        return report;
    }

    private String check() {
        if (approvedHttp != null) {
            long generation = fixture.base.barrier.state().generation();
            try (var ignored = fixture.base.barrier.enter(generation)) {
                return approvedHttp.check(() -> !fixture.base.barrier.isCurrent(generation));
            }
        }
        try (var probe = new HindsightReadinessService(connection, mapper, fixture.base.barrier)) { return probe.check().status(); }
    }
    private void verifyIdentities() {
        var tasks = worker.operations(); var expected = new HashSet<String>();
        state.path("sources").forEach(id -> expected.add(id.asText()));
        if (!state.path("prepared").asBoolean() || tasks.size() != SAMPLES.size() || expected.size() != SAMPLES.size()
                || !expected.equals(tasks.stream().map(MemorySyncRepository.Operation::sourceId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("没有准备好的三个合成来源，或来源身份已变化");
        if (approvedHttp != null) {
            var registrations = new ArrayList<ApprovedSyntheticHindsight.Source>(); var children = new HashSet<String>();
            var sources = new MemorySyncRepository(fixture.base.jdbc);
            var journal = new MemoryRemoteOperationRepository(fixture.base.jdbc, fixture.base.transactions);
            String space = state.path("bank").asText().substring(3);
            for (var task : tasks) {
                var source = sources.sourceForManagement(space, task.sourceId()).orElseThrow();
                String text = SAMPLES.stream().filter(value -> butvan.agent.agents.memory.MemorySourcePolicy.hash(value).equals(source.hash()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("来源哈希不属于固定合成内容"));
                registrations.add(new ApprovedSyntheticHindsight.Source(source.space(), source.document(), task.id(), text, source.occurredAt()));
                journal.children(task.id()).forEach(child -> children.add(child.id()));
            }
            approvedHttp.bind(registrations, children);
        }
    }
    private void verifySources() {
        verifyIdentities();
        Set<String> texts = new HashSet<>();
        for (var task : worker.operations()) texts.add(management.document(task.sourceId()).originalText());
        if (!texts.equals(new HashSet<>(SAMPLES))) throw new IllegalStateException("来源已变化，拒绝上传任何非固定合成文本");
    }
    private void save(String name, com.fasterxml.jackson.databind.JsonNode value) throws IOException {
        Path temporary = Files.createTempFile(root, ".trial-", ".json");
        try {
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            try { Files.move(temporary, root.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, root.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() throws IOException {
        if (gateway != null) gateway.close();
        if (lock != null) lock.release();
        if (channel != null) channel.close();
    }
}
