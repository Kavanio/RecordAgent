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
                  List<Task> operations, List<Match> matches, List<OperationResponse> deletions) { }
    private static final String SCHEMA = "recordagent-synthetic-trial-v1";
    private static final List<String> SAMPLES = List.of(
            "纯虚构测试：小舟尝试把每日手记翻成英文来练习英语，目前仍在摸索，并没有掌握英语。",
            "纯虚构测试：小舟周末练习摄影，发现光线不足时照片容易拍糊，准备下次再尝试。",
            "纯虚构测试：小舟原本打算周六阅读，后来改成周日；这是计划调整，不代表已经读完。"
    );
    private static final List<String> QUERIES = List.of(
            "虚构人物小舟尝试怎样练英语？", "虚构人物小舟拍照时遇到了什么问题？", "虚构人物小舟的阅读计划有什么调整？"
    );
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Path root;
    private final HindsightConnectionProperties connection = new HindsightConnectionProperties();
    private FileChannel channel;
    private FileLock lock;
    private HindsightHttpAdapter gateway;
    private MemorySyncFixture fixture;
    private MemorySyncService worker;
    private MemoryManagementService management;
    private ObjectNode state;

    MemorySyntheticTrial(Path directory, URI endpoint, String token, String chain) throws Exception {
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
            fixture = new MemorySyncFixture(root);
            var journal = new MemoryRemoteOperationRepository(fixture.base.jdbc, fixture.base.transactions);
            gateway = new HindsightHttpAdapter(endpoint, token, chain, journal, mapper);
            String fingerprint = gateway.recipientFingerprint();
            if (existing && !fingerprint.equals(state.path("recipient").asText()))
                throw new IllegalArgumentException("连接或处理链已变化，请先处理原试跑目标");
            state.put("recipient", fingerprint);
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
                fixture.base.service.authorize(new Authorization(0, gateway.recipientFingerprint(), true, true,
                        Set.of(Channel.DESKTOP), 1, 3, 30, true, "synthetic-grant"));
                fixture.base.service.saveSettings(0, true, true);
                for (String text : SAMPLES) fixture.finish(fixture.admit(text), MessageStatus.COMPLETED, true);
                var ids = state.putArray("sources"); worker.operations().forEach(operation -> ids.add(operation.sourceId()));
                state.put("prepared", true); save("trial.json", state);
            }
            verifySources(); status = "PREPARED";
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
        var report = new Report(action.name(), status, state.path("bank").asText(), false, state.path("recallUsed").asInt(), tasks, matches, deletions);
        save("latest-report.json", mapper.valueToTree(report));
        return report;
    }

    private String check() {
        try (var probe = new HindsightReadinessService(connection, mapper, fixture.base.barrier)) { return probe.check().status(); }
    }
    private void verifyIdentities() {
        var tasks = worker.operations(); var expected = new HashSet<String>();
        state.path("sources").forEach(id -> expected.add(id.asText()));
        if (!state.path("prepared").asBoolean() || tasks.size() != SAMPLES.size() || expected.size() != SAMPLES.size()
                || !expected.equals(tasks.stream().map(MemorySyncRepository.Operation::sourceId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("没有准备好的三个合成来源，或来源身份已变化");
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
