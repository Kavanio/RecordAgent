package butvan.agent.feishu.service;

import butvan.agent.agents.reset.ResetFiles;
import butvan.agent.agents.reset.ResetParticipant;
import butvan.agent.agents.reset.ResetScopeException;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.feishu.config.FeishuProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** 渠道持锁清理入口；不通过删除账本规避内存状态或文件锁。 */
@Component
@RequiredArgsConstructor
public class FeishuResetParticipant implements ResetParticipant {
    private final AgentStorageProperties storage;
    private final FeishuProperties properties;
    private final FeishuChannelLifecycle lifecycle;
    @Override public String id() { return "feishu"; }
    @Override public void resume() { lifecycle.start(); }
    @Override public void stop(String owner) { lifecycle.stop(); }
    @Override public void preflight(String owner) throws IOException { namespaces(owner); }
    @Override public void clean(String owner) throws IOException {
        for (Path namespace : namespaces(owner)) {
            try (var store = new FeishuTaskStore(namespace)) { store.resetPersonalState(); }
        }
    }
    private List<Path> namespaces(String owner) throws IOException {
        Path root = storage.getFeishuDirectory();
        ResetFiles.requireSafe(storage.getRootDirectory(), root);
        if (!Files.exists(root)) return List.of();
        var config = properties.load();
        String expected = FeishuTaskStore.stableId(config.appId() + ":" + owner);
        try (var stream = Files.list(root)) {
            var paths = stream.toList();
            for (Path path : paths) {
                ResetFiles.requireSafe(storage.getRootDirectory(), path);
                if (!path.getFileName().toString().equals(expected))
                    throw new ResetScopeException("存在无法确认归属的旧飞书应用账本，请先确认渠道归属后重试；未删除该账本。");
                try (var files = Files.list(path)) {
                    if (files.anyMatch(file -> !java.util.Set.of("state.json", "state.json.tmp", "channel.lock")
                            .contains(file.getFileName().toString())))
                        throw new ResetScopeException("飞书账本目录存在未归类副本，请先分类后重试；未删除该副本。");
                }
                try (var children = Files.walk(path)) {
                    for (Path child : children.toList()) ResetFiles.requireSafe(storage.getRootDirectory(), child);
                }
            }
            return paths;
        }
    }
}
