package butvan.agent.agents.context;

import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.review.LifeReviewContextPort;
import butvan.agent.agents.review.TaskReportContextPort;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.usage.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** 自动个人上下文的唯一组装 seam：画像、本地／远端检索、去重和硬预算统一收敛。 */
@Slf4j
@Component
public class ConversationContextAssembler {
    private final TokenCounter tokenCounter;
    private final PersonalContextService personalContextService;
    private final MemoryRecallPort local;
    private MemoryRecallPort remote = request -> MemoryRecallPort.Result.empty(MemoryRecallPort.Status.NOT_CONFIGURED);
    private LifeReviewContextPort lifeReviewContext = (owner, query) -> Optional.empty();
    private TaskReportContextPort taskReportContext = (owner, query) -> Optional.empty();
    private ResetBarrier resetBarrier;

    public ConversationContextAssembler(AgentStorageProperties storage, TokenCounter tokenCounter, PersonalContextService personalContextService) {
        this.tokenCounter = tokenCounter; this.personalContextService = personalContextService;
        this.local = new LocalMemoryRecallAdapter(storage);
    }
    /** 网络模块缺失时继续使用原有本地检索。 */
    @Autowired(required = false)
    public void setRemoteRecall(MemoryRecallPort recall) { this.remote = recall; }
    /** 仅在实际召回时解析可选复盘服务，避免经飞书渠道反向初始化 AgentService。 */
    @Autowired
    public void setLifeReviewContext(ObjectProvider<LifeReviewContextPort> provider) {
        this.lifeReviewContext = (owner, query) -> {
            LifeReviewContextPort context = provider.getIfAvailable();
            return context == null ? Optional.empty() : context.recallRecentReview(owner, query);
        };
    }
    /** 仅在实际召回时解析可选报告邮件上下文服务，避免与飞书投递端口形成初始化循环。 */
    @Autowired
    public void setTaskReportContext(ObjectProvider<TaskReportContextPort> provider) {
        this.taskReportContext = (owner, query) -> {
            TaskReportContextPort context = provider.getIfAvailable();
            return context == null ? Optional.empty() : context.recallRecentReport(owner, query);
        };
    }
    @Autowired(required = false)
    public void setResetBarrier(ResetBarrier barrier) { this.resetBarrier = barrier; }

    /** 一轮只组装一次；外部片段作为非可信资料，不能写回 AgentState 或 transcript。 */
    public ContextEnvelope assemble(ContextRequest request) {
        if (request == null || request.totalBudget() == 0 || request.cancelled().getAsBoolean()) return ContextEnvelope.empty();
        var profile = personalContextService.get(request.userId());
        if (!profile.enabled()) return ContextEnvelope.empty();
        long generation = resetBarrier == null ? -1 : resetBarrier.state().generation();
        BooleanSupplier current = () -> !request.cancelled().getAsBoolean()
                && personalContextService.get(request.userId()).enabled()
                && (resetBarrier == null || resetBarrier.isCurrent(generation));
        var blocks = new ArrayList<ContextBlock>();
        String profileSource = "legacy".equals(profile.source()) ? "MEMORY.md#User Profile" : "profile/PROFILE.md";
        var profileBlock = ContextEnvelopeBuilder.block(ContextKind.PROFILE, profileSource, profile.content(),
                Math.min(request.totalBudget(), request.profileBudget()), tokenCounter,
                () -> profile.revision().equals(personalContextService.get(request.userId()).revision()));
        if (profileBlock != null) blocks.add(profileBlock);
        int remaining = Math.min(request.memoryBudget(), request.totalBudget() - (profileBlock == null ? 0 : profileBlock.estimatedTokens()));
        if (remaining > 0 && request.memoryTopK() > 0 && !request.query().isBlank()) {
            var query = new MemoryRecallPort.Request(request.userId(), request.query(), remaining, request.memoryTopK(), request.channel(), request.cancelled());
            MemoryRecallPort.Result result;
            try {
                result = remote.recall(query);
                if (result.status() == MemoryRecallPort.Status.NOT_CONFIGURED) result = local.recall(query);
            } catch (RuntimeException exception) {
                log.warn("经历检索不可用，保留其他上下文：errorType={}", exception.getClass().getSimpleName());
                result = MemoryRecallPort.Result.empty(MemoryRecallPort.Status.UNAVAILABLE);
            }
            var seen = new HashSet<String>();
            if (result.status() == MemoryRecallPort.Status.PAUSED) {
                String reason = result.reason() == MemoryRecallPort.Reason.DELETION_UNRESOLVED
                        ? "删除未核实，保护屏障仍有效" : "当前开关或范围核对条件未满足";
                var notice = ContextEnvelopeBuilder.block(ContextKind.CONTROL, "memory-pipeline",
                        "本轮长期经历召回已暂停：" + reason
                                + "。本轮未注入长期经历；聊天历史与本地文件不能证明Hindsight保存或召回成功，保存进度以个人上下文处理状态为准。",
                        Math.min(remaining, 120), tokenCounter, current);
                if (notice != null) { blocks.add(notice); remaining -= notice.estimatedTokens(); }
            }
            int accepted = 0;
            var candidates = new ArrayList<MemoryRecallPort.Fragment>();
            try {
                lifeReviewContext.recallRecentReview(request.userId(), request.query()).ifPresent(candidates::add);
            } catch (RuntimeException exception) {
                log.warn("生活复盘上下文读取降级：errorType={}", exception.getClass().getSimpleName());
            }
            try {
                taskReportContext.recallRecentReport(request.userId(), request.query()).ifPresent(candidates::add);
            } catch (RuntimeException exception) {
                log.warn("任务报告邮件上下文读取降级：errorType={}", exception.getClass().getSimpleName());
            }
            candidates.addAll(result.fragments());
            for (var fragment : candidates) {
                if (accepted >= request.memoryTopK() || remaining <= 0) break;
                if (fragment.content() == null || PersonalContextSafety.containsSensitive(fragment.content())
                        || !seen.add(fragment.content().strip().replaceAll("\\s+", " "))) continue;
                var block = ContextEnvelopeBuilder.block(ContextKind.MEMORY, fragment.source(), fragment.content(), remaining,
                        tokenCounter, fragment.stillCurrent());
                if (block != null) { blocks.add(block); remaining -= block.estimatedTokens(); accepted++; }
            }
        }
        return ContextEnvelopeBuilder.build(blocks, request.totalBudget(), tokenCounter, current);
    }
}
