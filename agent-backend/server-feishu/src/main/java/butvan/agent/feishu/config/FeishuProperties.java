package butvan.agent.feishu.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书渠道配置读取服务。
 *
 * <p>只从本地 {@code ~/.butvan-agent/config.json} 的 feishu 节点读取配置，
 * 禁止把 App Secret 写入 yml、源码或日志。</p>
 */
@Slf4j
@Component
public class FeishuProperties {

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), ".butvan-agent", "config.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 读取当前生效的飞书配置；文件缺失、节点缺失或解析失败时返回禁用状态。
     *
     * @return 飞书配置
     */
    public FeishuConfigData load() {
        try {
            if (!Files.isRegularFile(CONFIG_PATH)) {
                return FeishuConfigData.disabled();
            }
            JsonNode root = objectMapper.readTree(CONFIG_PATH.toFile());
            if (root == null) return FeishuConfigData.disabled();
            JsonNode feishu = root.path("feishu");
            if (feishu.isMissingNode() || feishu.isNull()) {
                return FeishuConfigData.disabled();
            }
            List<String> senders = new ArrayList<>();
            feishu.path("allowedSenderIds").forEach(id -> { if (id.isTextual()) senders.add(id.asText()); });
            return new FeishuConfigData(
                    feishu.path("enabled").asBoolean(false),
                    feishu.path("appId").asText(""),
                    feishu.path("appSecret").asText(""), senders,
                    feishu.path("heartbeatSeconds").asInt(60),
                    feishu.path("executionTimeoutSeconds").asInt(600),
                    feishu.path("queueTimeoutSeconds").asInt(600),
                    feishu.path("maxQueuedPerSession").asInt(5),
                    feishu.path("maxConcurrentRuns").asInt(2)
            );
        } catch (IOException | RuntimeException e) {
            // Jackson 异常可能带配置原文，禁止记录异常消息或堆栈。
            log.error("读取飞书配置失败，渠道保持禁用：errorType={}", e.getClass().getSimpleName());
            return FeishuConfigData.disabled();
        }
    }
}
