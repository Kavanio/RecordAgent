package butvan.agent.feishu.service;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 启动时依据旧渠道账本标记会话；停用飞书时也执行，不联网、不改渠道账本或正文。 */
@Component
@RequiredArgsConstructor
public class FeishuSessionMigration {
    private final AgentStorageProperties storage;
    private final SessionCatalogService sessions;
    private final ResetBarrier barrier;

    /** 在应用提供服务前完成幂等迁移；重置期间不读取渠道文件或写入目录册。 */
    @PostConstruct
    public void migrate() {
        if (barrier.blocked()) return;
        try (var lease = barrier.enter()) {
            var directory = storage.getFeishuDirectory();
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(directory)) throw new IOException("unsafe channel directory");
            var ids = new HashSet<String>();
            var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            try (var namespaces = Files.list(directory)) {
                for (var namespace : namespaces.toList()) {
                    if (Files.isSymbolicLink(namespace)) throw new IOException("unsafe namespace");
                    if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) continue;
                    var file = namespace.resolve("state.json");
                    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (Files.isSymbolicLink(file) || Files.size(file) > FeishuTaskStore.MAX_BYTES)
                        throw new IOException("unsafe channel ledger");
                    // 只反序列化会话指针，跳过正文、凭据及其他投递字段。
                    var state = mapper.readValue(file.toFile(), SessionPointers.class);
                    if (state == null || state.version() != 1 || state.bindings() == null || state.tasks() == null)
                        throw new IOException("invalid channel ledger");
                    ids.addAll(state.bindings().values());
                    for (var task : state.tasks()) if (task != null && task.sessionId() != null) ids.add(task.sessionId());
                }
            }
            sessions.migrateFeishuSessions(ids);
        } catch (IOException exception) {
            // Jackson 原异常可能包含正文，启动错误只暴露固定提示。
            throw new IllegalStateException("飞书会话隔离迁移失败，请检查渠道账本或从备份恢复");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SessionPointers(int version, Map<String, String> bindings, List<TaskPointer> tasks) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskPointer(String sessionId) { }
}
