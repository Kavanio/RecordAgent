package butvan.agent.agents.prompts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 负责从环境识别与模块优先级组装生成最终 System Prompt 的建造者
 */
public class PromptBuilder {

    /**
     * 运行时环境上下文对象。
     *
     * @param workDir   当前智能体工作根目录
     * @param os        宿主操作系统名称（如 macos, windows 11, linux）
     * @param arch      宿主 CPU 架构（如 x86_64, aarch64）
     * @param shell     当前使用的终端 Shell 名称（如 bash, zsh, cmd.exe）
     * @param isGitRepo 当前工作目录是否处于 Git 仓库中
     * @param gitBranch 当前 Git 分支名称（若非 Git 仓库则为空）
     * @param model     当前调用的大语言模型标识/名称
     * @param date      当前系统 ISO 日期（格式：YYYY-MM-DD）
     */
    public record EnvironmentContext(
            String workDir,
            String os,
            String arch,
            String shell,
            boolean isGitRepo,
            String gitBranch,
            String model,
            String date
    ) {}

    /** 保存所有已添加的 PromptSection 模块列表 */
    public final List<PromptSection> sections = new ArrayList<>();

    public PromptBuilder add(PromptSection section) {
        if (section != null) {
            sections.add(section);
        }
        return this;
    }

    /**
     * 按 Priority 升序排序并用双换行拼接
     * @return
     */
    public String build() {
        // 关键：按 Priority 数值从小到大排序（优先级高/数值小的排在最前面）
        sections.sort(Comparator.comparingInt(PromptSection::priority));

        ArrayList<String> parts = new ArrayList<>();
        for (PromptSection s : sections) {
            String content = s.content() == null ? "" : s.content().strip();
            if (!content.isEmpty()) {
                parts.add(content);
            }
        }
        // 每个模块间保留双换行，保证 Markdown 标题与段落间距清晰
        return String.join("\n\n",parts);
    }

    /**
     * 自动探测当前宿主机的操作系统、Shell、CPU 架构以及 Git 仓库状态。
     *
     * @param modelName     当前选用的 LLM 模型名称
     * @param customWorkDir 自定义工作目录路径（若传入 null 或空白串，则默认使用当前 JVM 的 user.dir）
     * @return 探测填充后的 {@link EnvironmentContext} 环境对象
     */
    public static EnvironmentContext detectEnvironment(String modelName, String customWorkDir) {
        // 1. 解析工作目录
        String workDir = customWorkDir != null && !customWorkDir.isBlank()
                ? customWorkDir : System.getProperty("user.dir");

        // 2. 读取操作系统与架构
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String arch = System.getProperty("os.arch", "unknown");

        // 3. 读取用户 Shell 环境
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = os.contains("win") ? "cmd.exe" : "bash";
        }

        boolean isGitRepo = false;
        String gitBranch = "";

        // 4. 探测当前目录是否在 Git 仓库树内部
        try {
            Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--is-inside-work-tree")
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if ("true".equals(line != null ? line.strip() : "")) {
                    isGitRepo = true;
                }
            }
            p.waitFor();
        } catch (Exception ignored) {
            // 当系统没有安装 Git 或当前目录非 Git 仓库时捕获异常并静默处理
        }

        // 5. 若确认是 Git 仓库，获取当前 HEAD 对应的分支名称
        if (isGitRepo) {
            try {
                Process p = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                        .redirectErrorStream(true)
                        .start();
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        gitBranch = line.strip();
                    }
                }
                p.waitFor();
            } catch (Exception ignored) {
                // 分支检测失败时静默忽略，保持 gitBranch 为空字符串
            }
        }

        // 6. 获取当前本地 ISO 格式日期（YYYY-MM-DD）
        String date = LocalDate.now().toString();

        return new EnvironmentContext(workDir, os, arch, shell, isGitRepo, gitBranch, modelName, date);
    }


    /**
     * 构建普通会话（含日常聊天与随后的工程操作）使用的 System Prompt。
     *
     * @param modelName 当前模型名称
     * @param workDir 当前工作目录
     * @return 组装后的系统提示词
     */
    public static String buildDefaultSystemPrompt(String modelName, String workDir) {
        EnvironmentContext env = detectEnvironment(modelName, workDir);
        return engineeringSections(env)
                .add(PromptsSections.conversationSection())
                .add(PromptsSections.languageSection())
                .add(PromptsSections.judgementSection())
                .build();
    }

    /**
     * 构建项目会话使用的 System Prompt。
     *
     * <p>项目会话聚焦软件工程执行，不带上日常聊天、英语练习与生活建议模块；工具、权限、
     * 计划审批和验收要求保持不变。</p>
     *
     * @param modelName 当前模型名称
     * @param workDir 当前项目根目录
     * @return 组装后的系统提示词
     */
    public static String buildProjectSystemPrompt(String modelName, String workDir) {
        EnvironmentContext env = detectEnvironment(modelName, workDir);
        return engineeringSections(env).build();
    }

    /**
     * 组装两种会话共用的工程与安全模块。
     *
     * @param env 当前运行环境
     * @return 已加入共用模块的 builder
     */
    private static PromptBuilder engineeringSections(EnvironmentContext env) {
        return new PromptBuilder()
                .add(PromptsSections.identitySection())
                .add(PromptsSections.systemSection())
                .add(PromptsSections.contextRetrievalSection())
                .add(PromptsSections.doingTasksSection())
                .add(PromptsSections.planAcceptanceSection())
                .add(PromptsSections.executingActionsSection())
                .add(PromptsSections.usingToolsSection())
                .add(PromptsSections.toneStyleSection())
                .add(PromptsSections.outputEfficiencySection())
                .add(PromptsSections.environmentSection(env));
    }
}
