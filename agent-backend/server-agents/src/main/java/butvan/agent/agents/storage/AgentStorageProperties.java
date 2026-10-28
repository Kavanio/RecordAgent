package butvan.agent.agents.storage;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ButvanAgent 本地持久化目录的唯一入口。
 *
 * <p>所有服务都通过本类获取路径，禁止在其他类中手工拼接用户目录、
 * sessionDir 或 .agentscope 路径。</p>
 */
@Getter
@Component
public class AgentStorageProperties {

    /** ButvanAgent 的用户数据根目录，例如 /Users/xxx/.butvan-agent。 */
    private final Path rootDirectory;

    /** 保存侧边栏会话摘要的目录册文件。 */
    private final Path sessionCatalogFile;

    /** 保存本机已导入项目引用的目录册文件。 */
    private final Path projectCatalogFile;

    /** 保存用户可见消息记录的目录。 */
    private final Path transcriptDirectory;

    /** 保存非聊天模型调用用量的追加式账本。 */
    private final Path systemUsageFile;

    /** 保存尚未完成聊天轮次检查点的目录。 */
    private final Path runCheckpointDirectory;

    /** AgentScope AgentStateStore 的根目录。 */
    private final Path agentStateDirectory;

    /** AgentScope Workspace 的根目录。 */
    private final Path workspaceDirectory;

    /** 不含正文的持久化重置屏障与步骤日志。 */
    private final Path resetStateFile;

    /** 飞书渠道账本根目录，凭据仍归 config.json。 */
    private final Path feishuDirectory;

    /** 受保护的记忆服务试用连接；含凭据，恢复出厂保留，不属于工作区或业务表。 */
    private final Path hindsightConnectionFile;

    /** 天气与节假日持久化缓存目录。 */
    private final Path dailyContextDirectory;

    public AgentStorageProperties() {
        this(Paths.get(System.getProperty("user.home"), ".butvan-agent"));
    }

    /**
     * 使用显式数据根目录初始化存储路径，供隔离测试与后续可移植部署复用。
     *
     * @param rootDirectory ButvanAgent 拥有的数据根目录
     */
    public AgentStorageProperties(Path rootDirectory) {
        if (rootDirectory == null) throw new IllegalArgumentException("数据根目录不能为空");
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        sessionCatalogFile = this.rootDirectory.resolve("sessions").resolve("catalog.json");
        projectCatalogFile = this.rootDirectory.resolve("projects").resolve("catalog.json");
        transcriptDirectory = this.rootDirectory.resolve("transcripts");
        systemUsageFile = this.rootDirectory.resolve("usage").resolve("system-usage.jsonl");
        runCheckpointDirectory = this.rootDirectory.resolve("runs");
        agentStateDirectory = this.rootDirectory.resolve("agentscope").resolve("state");
        workspaceDirectory = this.rootDirectory.resolve("agentscope").resolve("workspace");
        resetStateFile = this.rootDirectory.resolve("reset/state.json");
        feishuDirectory = this.rootDirectory.resolve("channels/feishu");
        hindsightConnectionFile = this.rootDirectory.resolve("hindsight-connection.json");
        dailyContextDirectory = this.rootDirectory.resolve("daily-context");

        // 应用启动的时候创建本项目明确拥有的目录
        createDirectories(sessionCatalogFile.getParent());
        createDirectories(projectCatalogFile.getParent());
        createDirectories(transcriptDirectory);
        createDirectories(systemUsageFile.getParent());
        createDirectories(runCheckpointDirectory);
        createDirectories(agentStateDirectory);
        createDirectories(workspaceDirectory);
        createDirectories(dailyContextDirectory);
    }

    /**
     * 根据受后端控制的 UUID 得到该会话的消息文件
     * @param sessionId
     * @return
     */
    public Path transcriptFile(String sessionId) {
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("会话ID 格式非法");
        }

        return transcriptDirectory.resolve(sessionId + ".jsonl");
    }

    /** 根据受控 turnId 返回运行中检查点文件。 */
    public Path runCheckpointFile(String turnId) {
        if (turnId == null || !turnId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("轮次ID 格式非法");
        }
        return runCheckpointDirectory.resolve(turnId + ".json");
    }

    /** 返回隔离后的用户工作区，供个人上下文与记忆模块统一取址。 */
    public Path userWorkspaceDirectory(String userId) {
        if (userId == null || !userId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("用户ID 格式非法");
        }
        Path directory = workspaceDirectory.resolve(userId);
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("用户工作区不能是符号链接");
        }
        return directory;
    }

    /** 返回用户显式维护的画像文件。 */
    public Path personalContextProfileFile(String userId) {
        return userWorkspaceDirectory(userId).resolve("profile").resolve("PROFILE.md");
    }

    /** 返回个人上下文开关设置文件。 */
    public Path personalContextSettingsFile(String userId) {
        return userWorkspaceDirectory(userId).resolve("profile").resolve("settings.json");
    }

    /** 返回画像维护状态文件。 */
    public Path personalContextMaintenanceFile(String userId) {
        return userWorkspaceDirectory(userId).resolve("profile").resolve("maintenance.json");
    }

    /** 返回当前唯一待审核画像提案文件。 */
    public Path personalContextPendingProposalFile(String userId) {
        return userWorkspaceDirectory(userId).resolve("profile").resolve("proposals").resolve("pending.json");
    }

    /** 返回已确认画像的历史版本目录。 */
    public Path personalContextHistoryDirectory(String userId) {
        return userWorkspaceDirectory(userId).resolve("profile").resolve("history");
    }

    private void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法初始化本地数据目录:" + directory, e);
        }
    }
}
