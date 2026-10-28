package butvan.agent.agents.reset;

import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 当前单用户应用的个人文件白名单；未知 workspace 内容必须人工分类后才能继续。 */
@Component
@RequiredArgsConstructor
public class AgentResetFiles {
    private final AgentStorageProperties storage;
    private static final Set<String> PERSONAL = Set.of("MEMORY.md", "memory", "profile", "agents", "plans", "bus");

    /** 全部校验先于删除；不接受客户端路径，也不遍历项目引用目录。 */
    public void preflight(String owner) throws IOException {
        if (!"local-default".equals(owner)) throw new ResetScopeException("尚不支持多用户文件重置");
        for (Path path : targets(owner)) {
            ResetFiles.requireSafe(storage.getRootDirectory(), path);
            if (Files.exists(path)) try (var walk = Files.walk(path)) {
                for (Path child : walk.toList()) ResetFiles.requireSafe(storage.getRootDirectory(), child);
            }
        }
        requireKnownChildren(storage.getAgentStateDirectory(), Set.of(owner), "AgentState 所有者目录");
        Path workspace = storage.getWorkspaceDirectory();
        requireKnownChildren(workspace, Set.of(owner, "bus", "agents", ".index"), "应用 workspace 根目录");
        requireKnownChildren(storage.userWorkspaceDirectory(owner), PERSONAL, "个人 workspace（含 AGENTS.md、rules、knowledge 等未归类内容）");
        requireKnownChildren(workspace.resolve(".index"), Set.of("workspace.db", "workspace.db-wal", "workspace.db-shm", "workspace.db-journal"), "框架索引目录");
        Path catalog = storage.getSessionCatalogFile();
        if (Files.exists(catalog)) {
            var rows = new ObjectMapper().readTree(Files.readAllBytes(catalog));
            if (!rows.isArray()) throw new ResetScopeException("会话目录册格式不支持，请先修复后重试");
            for (var row : rows) if (!owner.equals(row.path("ownerId").asText()))
                throw new ResetScopeException("会话包含其他所有者，需先分类");
        }
    }
    /** 幂等清理后写入显式空画像和关闭设置，阻断 MEMORY 回退与自动学习。 */
    public void clean(String owner) throws IOException {
        preflight(owner);
        for (Path path : targets(owner)) ResetFiles.delete(storage.getRootDirectory(), path);
        Files.createDirectories(storage.getTranscriptDirectory());
        Files.createDirectories(storage.getRunCheckpointDirectory());
        Path profile = storage.personalContextProfileFile(owner);
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, "");
        Files.writeString(storage.personalContextSettingsFile(owner), "{\"enabled\":false,\"maintenanceEnabled\":false}");
    }
    private List<Path> targets(String owner) {
        var paths = new ArrayList<>(List.of(storage.getSessionCatalogFile(),
                storage.getSessionCatalogFile().resolveSibling("catalog.json.tmp"), storage.getTranscriptDirectory(),
                storage.getRunCheckpointDirectory(), storage.getAgentStateDirectory().resolve(owner),
                storage.getWorkspaceDirectory().resolve("bus"),
                storage.getWorkspaceDirectory().resolve("agents"), storage.getWorkspaceDirectory().resolve(".index")));
        for (String name : PERSONAL) paths.add(storage.userWorkspaceDirectory(owner).resolve(name));
        return paths;
    }
    private void requireKnownChildren(Path path, Set<String> known, String label) throws IOException {
        ResetFiles.requireSafe(storage.getRootDirectory(), path);
        if (!Files.exists(path)) return;
        try (var children = Files.list(path)) {
            if (children.anyMatch(child -> !known.contains(child.getFileName().toString())))
                throw new ResetScopeException(label + "存在归属不明内容，请先分类后重试。真实项目不在清理范围。");
        }
    }
}
