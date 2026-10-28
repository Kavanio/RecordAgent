package butvan.agent.agents.prompts;

import butvan.agent.agents.usage.ApproximateTokenCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultPromptIncludesOnDemandContextRulesInPriorityOrder() {
        String prompt = buildDefaultPrompt();

        assertTrue(prompt.contains("# 工作区上下文按需加载"));
        assertTrue(prompt.contains("memory_search"));
        assertTrue(prompt.contains("AGENTS.md"));
        assertTrue(prompt.indexOf("# 系统运行规则")
                < prompt.indexOf("# 工作区上下文按需加载"));
        assertTrue(prompt.indexOf("# 工作区上下文按需加载")
                < prompt.indexOf("# 任务执行规范"));
    }

    @Test
    void defaultPromptDoesNotDuplicateToneStyleSection() {
        assertEquals(1, occurrences(buildDefaultPrompt(), "# 语气与格式"));
    }

    @Test
    void ordinaryConversationDoesNotRequirePlansOrAcceptanceReports() {
        String prompt = buildDefaultPrompt();

        assertTrue(prompt.contains("# 普通聊天"));
        assertTrue(prompt.contains("普通聊天不要求计划和验收报告"));
        assertTrue(prompt.contains("一次最多问一个必要问题"));
        assertTrue(prompt.contains("工程或业务操作"));
        assertTrue(prompt.contains("PermissionContext"));
        assertTrue(prompt.contains("acceptance_report"));
        assertTrue(prompt.contains("普通聊天不进入计划模式"));
    }

    @Test
    void bothPromptVariantsKeepSharedSafetyAndToolRules() {
        for (String prompt : List.of(buildDefaultPrompt(),
                PromptBuilder.buildProjectSystemPrompt("test-model", temporaryDirectory.toString()))) {
            assertTrue(prompt.contains("# 高风险动作审查"));
            assertTrue(prompt.contains("# 工具调度规范"));
            assertTrue(prompt.contains("# 工作区上下文按需加载"));
            assertTrue(prompt.contains("PermissionContext"));
        }
    }

    @Test
    void projectPromptKeepsEngineeringRulesButDropsCompanionBehaviour() {
        String prompt = PromptBuilder.buildProjectSystemPrompt("test-model", temporaryDirectory.toString());

        assertTrue(prompt.contains("# 任务执行规范"));
        assertTrue(prompt.contains("acceptance_report"));
        assertTrue(prompt.contains("PermissionContext"));
        assertFalse(prompt.contains("# 普通聊天"));
        assertFalse(prompt.contains("# 语言与英语"));
        assertTrue(new ApproximateTokenCounter().count(prompt) <= 1_200);
    }

    @Test
    void defaultPromptCarriesLanguageAndJudgementRules() {
        String prompt = buildDefaultPrompt();

        assertTrue(prompt.contains("# 语言与英语"));
        assertTrue(prompt.contains("# 判断与来源"));
        assertTrue(prompt.contains("不因过去学过英语就每句双语"));
        assertTrue(prompt.contains("没核对就不加引号"));
    }

    @Test
    void defaultPromptStaysWithinHardTokenBudget() {
        int tokens = new ApproximateTokenCounter().count(buildDefaultPrompt());

        assertTrue(tokens <= 1_200, "System Prompt 超过 1200 Token，实际为 " + tokens);
    }

    private String buildDefaultPrompt() {
        return PromptBuilder.buildDefaultSystemPrompt("test-model", temporaryDirectory.toString());
    }

    private int occurrences(String text, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
