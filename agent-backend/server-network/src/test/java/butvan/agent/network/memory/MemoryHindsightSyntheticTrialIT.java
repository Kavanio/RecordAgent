package butvan.agent.network.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 手动指定 -Dtest 才发现的真实服务试跑；普通 test 不发现 IT，也无任何生产装配。 */
@EnabledIfEnvironmentVariable(named = "RECORDAGENT_MEMORY_TRIAL_OPT_IN",
        matches = "CONFIRM_3_SYNTHETIC_WRITES_3_RECALLS_UNKNOWN_DELETE")
class MemoryHindsightSyntheticTrialIT {
    @Test void runOneExplicitSyntheticStep() throws Exception {
        Path root = Path.of(required("RECORDAGENT_MEMORY_TRIAL_ROOT"));
        if (!root.isAbsolute() || !Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
                .equals(root.toAbsolutePath().normalize().resolve("process-home")))
            throw new IllegalArgumentException("必须指定独立试跑目录，并将测试 JVM user.home 指向其 process-home");
        MemorySyntheticTrial.Action action;
        try { action = MemorySyntheticTrial.Action.valueOf(required("RECORDAGENT_MEMORY_TRIAL_ACTION")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("试跑步骤须为 PREPARE／CHECK／SYNC／RECALL／WITHDRAW／STATUS"); }
        URI endpoint;
        try { endpoint = URI.create(required("RECORDAGENT_MEMORY_TRIAL_ENDPOINT")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("试跑连接地址格式无效，请检查受保护配置"); }
        try (var trial = new MemorySyntheticTrial(root, endpoint,
                System.getenv().getOrDefault("RECORDAGENT_MEMORY_TRIAL_TOKEN", ""), required("RECORDAGENT_MEMORY_TRIAL_PROCESSING_CHAIN"),
                "CONFIRM_FIXED_URL_SYNTHETIC_NO_TOKEN".equals(System.getenv("RECORDAGENT_MEMORY_TRIAL_PLAINTEXT_ACK")))) {
            var report = trial.run(action);
            System.out.println(formatReport(report));
            assertFalse(report.remoteErasureVerified(), "本试跑从不宣告完整物理删除");
            String expected = switch (action) {
                case PREPARE -> "PREPARED";
                case CHECK -> "INSPECTED";
                case SYNC -> "SYNC_CHECKED";
                case RECALL -> "SUCCESS";
                case WITHDRAW -> "WITHDRAWN_REMOTE_UNVERIFIED";
                case STATUS -> "LOCAL_STATUS";
            };
            assertEquals(expected, report.status(), "本步骤未达到预期；保留同一试跑目录，查看 latest-report.json，不要盲目重跑写入");
        }
    }
    static String formatReport(MemorySyntheticTrial.Report report) throws com.fasterxml.jackson.core.JsonProcessingException {
        return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writerWithDefaultPrettyPrinter().writeValueAsString(report);
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少受保护的试跑配置：" + name);
        return value;
    }
}
