package butvan.agent.network.memory.client;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.network.memory.dto.MemoryTrialDtos.*;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.service.MemoryRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 明确确认后才接通真实聊天试用；配置连接不产生聊天处理授权，也不读取或补传历史。 */
@Slf4j
@Service
public class HindsightTrialService implements MemoryRemoteGateway, MemoryWriteGateway, MemoryRecallGateway, AutoCloseable {
    private static final String DEFAULT_CHAIN = "此服务器现有处理配置（详细模型链未自动核实）";
    private final HindsightTrialStore store;
    private final MemoryConnectionRepository connections;
    private final MemoryRemoteOperationRepository operations;
    private final ObjectMapper mapper;
    private final ResetBarrier barrier;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile Snapshot active;
    private volatile boolean loadFailed;
    private volatile boolean closed;
    private record Snapshot(HindsightTrialStore.Configuration config, HindsightHttpAdapter gateway) { }

    public HindsightTrialService(HindsightTrialStore store, MemoryConnectionRepository connections,
                                 MemoryRemoteOperationRepository operations, ObjectMapper mapper, ResetBarrier barrier) {
        this.store = store; this.connections = connections; this.operations = operations; this.mapper = mapper; this.barrier = barrier;
        try {
            var saved = store.read();
            if (saved.isPresent()) {
                var config = saved.get();
                if (config.version() < 1 || config.version() == Long.MAX_VALUE || config.requestId() == null || !config.trialRisksAccepted())
                    throw new IOException("记忆连接配置无效");
                active = new Snapshot(config, gateway(config));
            }
        } catch (IOException | RuntimeException failure) {
            loadFailed = true;
            log.warn("记忆连接配置未加载，保持关闭：errorType={}", failure.getClass().getSimpleName());
        }
    }
    /** 读取只返回安全摘要，不触发网络检查，不回显 Token。 */
    public Connection connection() {
        try (var ignored = barrier.enter()) { return response(); }
    }
    private Connection response() {
        var current = active;
        if (current == null || closed) return new Connection(0, false, "", "", false, false, "",
                loadFailed ? "记忆连接文件不可读取，已保持关闭；请恢复配置文件，不会覆盖原凭据" : "尚未配置真实聊天试用连接");
        var config = current.config();
        return new Connection(config.version(), true, config.endpoint(), config.processingChain(),
                URI.create(config.endpoint()).getScheme().equals("https"), !config.token().isEmpty(), current.gateway().recipientFingerprint(),
                "真实聊天试用连接已保存；完整远端删除未核实，仍须另行授权并开启保存／召回", config.bankId());
    }
    /** 显式检查只读合约后保存连接；版本与 requestId 防止重复探测及静默覆盖。 */
    public Connection connect(Connect input) {
        if (input == null || !input.acceptTrialRisks() || input.expectedVersion() < 0 || input.endpoint() == null
                || input.endpoint().length() > 2048 || input.requestId() == null || !input.requestId().matches("[a-zA-Z0-9_-]{1,80}"))
            throw new MemoryRequestException(400, "请填写服务地址，并明确确认真实聊天试用与删除限制");
        if (loadFailed) throw new MemoryRequestException(409, "原记忆连接文件不可读取，请先恢复原配置；不会覆盖原凭据");
        long generation = barrier.state().generation();
        try (var ignored = barrier.enter(generation)) {
            boolean acquired;
            try { acquired = lock.writeLock().tryLock(1, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new MemoryRequestException(409, "连接操作已取消"); }
            if (!acquired) throw new MemoryRequestException(409, "记忆调用仍在进行，请稍后配置连接");
            try { return configure(input, generation); }
            finally { lock.writeLock().unlock(); }
        }
    }
    private Connection configure(Connect input, long generation) {
        if (closed) throw new MemoryRequestException(409, "记忆服务正在关闭");
        var previous = active;
        String endpoint = input.endpoint().strip();
        if (!endpoint.endsWith("/")) endpoint += "/";
        String token = input.clearToken() ? "" : input.token();
        if (token == null) token = previous != null && previous.config().endpoint().equals(endpoint) ? previous.config().token() : "";
        String chain = input.processingChain() == null || input.processingChain().isBlank() ? DEFAULT_CHAIN : input.processingChain().strip();
        if (chain.length() > 400) throw new MemoryRequestException(400, "处理配置说明最多 400 字符");
        long version = previous == null ? 0 : previous.config().version();
        String bankId = input.bankId() == null ? (previous == null ? "" : previous.config().bankId()) : input.bankId().strip();
        if (!bankId.isEmpty() && !HindsightHttpAdapter.identifier(bankId))
            throw new MemoryRequestException(400, "记忆库 ID 只允许 1—128 个英文字母、数字、下划线或连字符");
        var config = new HindsightTrialStore.Configuration(version + 1, endpoint, token, chain, input.allowPlaintext(), true, input.requestId(), bankId);
        HindsightHttpAdapter candidate;
        try { candidate = gateway(config); }
        catch (IllegalArgumentException invalid) { throw new MemoryRequestException(400, "连接格式无效；公网 HTTP 须明确确认且不能携带 Token，地址不能包含凭据、查询或片段"); }
        boolean installed = false;
        try {
            if (previous != null && previous.config().requestId().equals(input.requestId())
                    && previous.gateway().recipientFingerprint().equals(candidate.recipientFingerprint()) && version == input.expectedVersion() + 1)
                return response();
            if (input.expectedVersion() != version) throw new MemoryRequestException(409, "连接配置已变化，请刷新后重新确认");
            if (previous != null && previous.config().requestId().equals(input.requestId()))
                throw new MemoryRequestException(409, "此连接请求 ID 已用于另一项配置");
            inspect(config, generation);
            if (!barrier.isCurrent(generation)) throw new MemoryRequestException(409, "Agent 状态已变化，未切换连接");
            connections.change(candidate.recipientFingerprint(), () -> {
                if (!barrier.isCurrent(generation)) throw new MemoryRequestException(409, "Agent 状态已变化，未切换连接");
                try { store.write(config); }
                catch (IOException failure) { throw new MemoryRequestException(503, "记忆连接保存失败，未覆盖原配置"); }
                active = new Snapshot(config, candidate); return null;
            });
            installed = true;
            if (previous != null) previous.gateway().close();
            return response();
        } finally { if (!installed && (active == null || active.gateway() != candidate)) candidate.close(); }
    }
    private HindsightHttpAdapter gateway(HindsightTrialStore.Configuration config) {
        return HindsightHttpAdapter.forTrial(URI.create(config.endpoint()), config.token(), config.processingChain(), operations, mapper, config.allowPlaintext(), config.bankId());
    }
    /** 三次 GET 共享 2500ms；不读取 bank、不调用模型，不把合约检查当作删除证明。 */
    private void inspect(HindsightTrialStore.Configuration config, long generation) {
        try (var http = new HindsightHttpTransport(mapper)) {
            long deadline = System.nanoTime() + Duration.ofMillis(2500).toNanos();
            URI endpoint = URI.create(config.endpoint()); BooleanSupplier cancel = () -> !barrier.isCurrent(generation);
            var version = http.request("GET", endpoint.resolve("version"), config.token(), null, deadline, cancel);
            if (version.status() != 200 || !version.body().path("api_version").asText().equals("1.1.0"))
                throw new MemoryRequestException(409, "记忆服务版本不符合 1.1.0 合约，未切换连接");
            var health = http.request("GET", endpoint.resolve("health/ready"), config.token(), null, deadline, cancel);
            if (health.status() != 200 || !health.body().path("status").asText().equals("healthy"))
                throw new MemoryRequestException(409, "记忆服务尚未就绪，未切换连接");
            var schema = http.request("GET", endpoint.resolve("openapi.json"), config.token(), null, deadline, cancel);
            if (schema.status() != 200 || !HindsightContractCheck.supports(schema.body()))
                throw new MemoryRequestException(409, "记忆服务缺少必要接口字段，未切换连接");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw new MemoryRequestException(409, "连接检查已取消，未切换连接");
        } catch (IOException failure) {
            log.warn("记忆试用连接检查失败：errorType={}", failure.getClass().getSimpleName());
            throw new MemoryRequestException(503, "无法完成记忆服务连接检查，请核对地址和服务状态");
        }
    }
    /** 指纹读取不等待网络锁，避免与持有数据库短事务的来源登记互锁。 */
    public String recipientFingerprint() { var current = active; return closed || current == null ? "" : current.gateway().recipientFingerprint(); }
    private <T> T invoke(Supplier<T> absent, java.util.function.Function<HindsightHttpAdapter, T> call) {
        if (!lock.readLock().tryLock()) return absent.get();
        try { var current = active; return closed || current == null ? absent.get() : call.apply(current.gateway()); }
        finally { lock.readLock().unlock(); }
    }
    public Outcome submit(Write request, BooleanSupplier cancelled) { return invoke(() -> Outcome.NOT_SUBMITTED_RETRYABLE, gateway -> gateway.submit(request, cancelled)); }
    public Outcome query(String id, Target target, BooleanSupplier cancelled) { return invoke(() -> Outcome.UNKNOWN, gateway -> gateway.query(id, target, cancelled)); }
    public List<Candidate> recall(Query query, Control control) { return invoke(List::of, gateway -> gateway.recall(query, control)); }
    public DeletionResult deleteAndVerify(Target target) { return invoke(() -> DeletionResult.UNKNOWN, gateway -> gateway.deleteAndVerify(target)); }
    @PreDestroy public void close() {
        lock.writeLock().lock();
        try { closed = true; if (active != null) active.gateway().close(); }
        finally { lock.writeLock().unlock(); }
    }
}
