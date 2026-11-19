package butvan.agent.agents.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 从用户级 {@code ~/.butvan-agent/config.json} 动态读写电脑桌面操控配置（{@code desktopControl} 节点）。
 */
@Slf4j
@Component
public class DesktopControlProperties {

    private static final int MAX_CLI_PATH_LENGTH = 512;

    private static final Path DEFAULT_CONFIG_PATH = Paths.get(
            System.getProperty("user.home"), ".butvan-agent", "config.json");

    private final ObjectMapper objectMapper;
    private final Path configPath;

    @Autowired
    public DesktopControlProperties(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_CONFIG_PATH);
    }

    /** 测试专用构造器，允许注入临时配置文件路径。 */
    public DesktopControlProperties(ObjectMapper objectMapper, Path configPath) {
        this.objectMapper = objectMapper;
        this.configPath = configPath;
    }

    /**
     * 读取最新的桌面操控配置；文件或节点缺失、解析异常时保守返回关闭态。
     */
    public DesktopControlConfigData load() {
        try {
            if (!Files.isRegularFile(configPath)) return DesktopControlConfigData.disabled();

            JsonNode root = objectMapper.readTree(configPath.toFile());
            JsonNode node = root == null ? null : root.path("desktopControl");
            if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
                return DesktopControlConfigData.disabled();
            }

            boolean enabled = node.path("enabled").asBoolean(false);
            String cliPath = sanitizeLoadedCliPath(node.path("cliPath").asText(""));
            return new DesktopControlConfigData(enabled, cliPath);
        } catch (IOException | RuntimeException exception) {
            log.error("读取 desktopControl 配置失败，桌面操控将保持关闭：{}", configPath, exception);
            return DesktopControlConfigData.disabled();
        }
    }

    /**
     * 仅更新启用开关，同时保留 config.json 中的其他节点及既有 cliPath。
     */
    public synchronized DesktopControlConfigData updateEnabled(boolean enabled) {
        return updateConfig(enabled, null);
    }

    /**
     * 更新桌面操控配置；当 {@code cliPath} 为 {@code null} 时保留已保存路径，
     * 同时原样保留 config.json 中的所有其他配置节点。
     */
    public synchronized DesktopControlConfigData updateConfig(boolean enabled, String cliPath) {
        String normalizedPath = cliPath == null ? null : validateCliPath(cliPath);
        try {
            ObjectNode root = readConfigRoot();
            JsonNode existing = root.get("desktopControl");
            ObjectNode node = existing instanceof ObjectNode objectNode
                    ? objectNode
                    : objectMapper.createObjectNode();

            node.put("enabled", enabled);
            if (normalizedPath != null) {
                node.put("cliPath", normalizedPath);
            } else if (!node.has("cliPath")) {
                node.put("cliPath", "");
            }
            root.set("desktopControl", node);

            writeAtomically(root);
            return load();
        } catch (IOException exception) {
            log.error("保存 desktopControl 配置失败：{}", configPath, exception);
            throw new IllegalStateException("电脑操控配置保存失败", exception);
        }
    }

    private ObjectNode readConfigRoot() throws IOException {
        if (!Files.isRegularFile(configPath)) return objectMapper.createObjectNode();
        JsonNode root = objectMapper.readTree(configPath.toFile());
        if (root instanceof ObjectNode objectNode) return objectNode;
        throw new IOException("config.json 根节点不是对象");
    }

    private void writeAtomically(ObjectNode root) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tempFile = Files.createTempFile(parent != null ? parent : Path.of("."), "config-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), root);
            try {
                Files.move(tempFile, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(tempFile);
            throw exception;
        }
    }

    private static String validateCliPath(String raw) {
        String stripped = raw.strip();
        if (stripped.length() > MAX_CLI_PATH_LENGTH) {
            throw new IllegalArgumentException("CLI 路径长度不能超过 " + MAX_CLI_PATH_LENGTH + " 个字符");
        }
        if (stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0 || stripped.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("CLI 路径不能包含换行符或非法控制字符");
        }
        return stripped;
    }

    private static String sanitizeLoadedCliPath(String raw) {
        if (raw == null) return "";
        String stripped = raw.strip();
        if (stripped.length() > MAX_CLI_PATH_LENGTH
                || stripped.indexOf('\n') >= 0
                || stripped.indexOf('\r') >= 0
                || stripped.indexOf('\0') >= 0) {
            return "";
        }
        return stripped;
    }
}
