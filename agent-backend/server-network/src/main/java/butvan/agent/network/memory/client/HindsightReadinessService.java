package butvan.agent.network.memory.client;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.network.memory.dto.MemoryDtos.ConnectionTestResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/** 显式只读就绪检查；不会读取 bank、发送正文、产生授权或启用生产 Gateway。 */
@Slf4j
@Service
public class HindsightReadinessService implements AutoCloseable {
    private final HindsightConnectionProperties properties;
    private final ResetBarrier barrier;
    private final HindsightProbeHttpClient http;
    private final Semaphore producers = new Semaphore(1);
    private final Duration timeout;

    @Autowired
    public HindsightReadinessService(HindsightConnectionProperties properties, ObjectMapper mapper, ResetBarrier barrier) {
        this(properties, mapper, barrier, Duration.ofMillis(2500));
    }
    /** 测试可缩短总期限，不能扩大生产上限。 */
    HindsightReadinessService(HindsightConnectionProperties properties, ObjectMapper mapper, ResetBarrier barrier, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMillis(2500)) > 0)
            throw new IllegalArgumentException("记忆探测期限无效");
        this.properties = properties; this.barrier = barrier; this.http = new HindsightProbeHttpClient(mapper); this.timeout = timeout;
    }

    /** 只有用户显式触发时依次读取 version／ready／OpenAPI，三次 GET 共用 2500ms。 */
    public ConnectionTestResponse check() {
        long generation = barrier.state().generation();
        try (var ignored = barrier.enter(generation)) {
            String endpoint = properties.getEndpoint();
            String token = properties.getToken() == null ? "" : properties.getToken();
            if (!properties.isProbeEnabled() || endpoint == null || endpoint.isBlank())
                return result("NOT_CONFIGURED", false, false, false, false, null, false, !token.isBlank(), "尚未配置只读连接检查，记忆保存与召回仍未接入");
            URI base;
            try {
                base = validate(endpoint, token);
            } catch (IllegalArgumentException invalid) {
                return result("INVALID_CONFIGURATION", true, false, false, false, null, false, !token.isBlank(), "记忆服务地址或凭据格式无效，请检查后端受保护配置");
            }
            boolean secure = base.getScheme().equals("https");
            if (!secure && !loopback(base))
                return result("INSECURE_TRANSPORT", true, false, false, false, null, false, !token.isBlank(), "公网记忆服务需要 HTTPS；未向 HTTP 地址发送请求或凭据");
            if (!producers.tryAcquire())
                return result("BUSY", true, secure, false, false, null, false, !token.isBlank(), "已有连接检查正在进行，请稍后重试");
            boolean reachable = false, ready = false;
            String version = null;
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                var versionInfo = http.get(base.resolve("version"), token, deadline, () -> !barrier.isCurrent(generation));
                reachable = true;
                String candidate = versionInfo.path("api_version").asText();
                version = candidate.matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}") ? candidate : null;
                if (!"1.1.0".equals(version)) return result("VERSION_MISMATCH", true, secure, true, false, version, false, !token.isBlank(), "当前服务版本不符合已核实合约，保持记忆入口关闭");
                var health = http.get(base.resolve("health/ready"), token, deadline, () -> !barrier.isCurrent(generation));
                ready = health.path("status").asText().equals("healthy");
                if (!ready) return result("NOT_READY", true, secure, true, false, version, false, !token.isBlank(), "记忆服务尚未就绪");
                var api = http.get(base.resolve("openapi.json"), token, deadline, () -> !barrier.isCurrent(generation));
                boolean contract = HindsightContractCheck.supports(api);
                return result(contract ? "INSPECTED" : "CONTRACT_MISMATCH", true, secure, true, true, version, contract, !token.isBlank(),
                        contract ? "只读检查通过；异步任务正文及派生删除证据尚未闭环，保存和召回继续关闭" : "服务缺少必要合约字段，保持记忆入口关闭");
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return result("CANCELLED", true, secure, reachable, ready, version, false, !token.isBlank(), "连接检查已取消，未启用记忆处理");
            } catch (IOException | RuntimeException failure) {
                log.warn("记忆只读连接检查失败：errorType={}", failure.getClass().getSimpleName());
                return result("UNAVAILABLE", true, secure, reachable, ready, version, false, !token.isBlank(), "连接检查未完成或响应无效，请核对服务就绪状态后重试");
            } finally { producers.release(); }
        }
    }

    private URI validate(String value, String token) {
        URI uri = URI.create(value.strip());
        if (uri.getHost() == null || uri.getScheme() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")) || uri.getPort() > 65535 || uri.getPort() == 0
                || token.length() > 8192 || token.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("记忆连接配置无效");
        String path = uri.getRawPath();
        if (path != null && (path.contains("%") || path.contains("..") || path.contains("//")))
            throw new IllegalArgumentException("记忆连接路径无效");
        return URI.create(uri.toString().endsWith("/") ? uri.toString() : uri + "/");
    }
    private boolean loopback(URI uri) {
        return java.util.Set.of("127.0.0.1", "[::1]", "::1", "localhost").contains(uri.getHost().toLowerCase(Locale.ROOT));
    }
    private ConnectionTestResponse result(String status, boolean configured, boolean secure, boolean reachable, boolean ready,
                                          String version, boolean contract, boolean credentials, String message) {
        return new ConnectionTestResponse(status, configured, secure, reachable, ready, version, contract, false, credentials, message);
    }
    @Override @PreDestroy public void close() { http.close(); }
}
