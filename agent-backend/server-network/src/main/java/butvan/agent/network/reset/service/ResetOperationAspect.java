package butvan.agent.network.reset.service;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.session.AgentStreamSession;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 组合根统一登记个人状态操作；异步聊天租约持续至持久化收尾，事务提交包含在租约内。 */
@Aspect
@Component
@Order(-100)
@RequiredArgsConstructor
public class ResetOperationAspect {
    private final ResetBarrier barrier;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    @Around("execution(public * butvan.agent.agents.agent.AgentService.*(..)) || "
            + "execution(public * butvan.agent.agents.session.*Service.*(..)) || "
            + "execution(public * butvan.agent.agents.context.*Service.*(..)) || "
            + "execution(public * butvan.agent.agents.subagent.SubagentTaskService.*(..)) || "
            + "execution(public * butvan.agent.network.review.service.*Service.*(..)) || "
            + "execution(public * butvan.agent.network.usage.service.TokenUsageIndexService.*(..)) || "
            + "execution(public * butvan.agent.network.memory.service.MemoryService.*(..)) || "
            + "execution(public * butvan.agent.network.memory.service.MemoryManagementService.*(..))")
    public Object guard(ProceedingJoinPoint call) throws Throwable {
        if (depth.get() > 0) return call.proceed();
        Long expectedGeneration = null;
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            var request = servlet.getRequest();
            if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                String value = request.getHeader("X-Agent-Generation");
                if (value == null || !value.matches("[0-9]{1,18}"))
                    throw new ResetRequestException(428, "请先读取重置状态并携带当前 Agent 代次");
                expectedGeneration = Long.parseLong(value);
            }
        }
        var lease = barrier.enter(expectedGeneration);
        boolean transferred = false;
        depth.set(1);
        try {
            Object result = call.proceed();
            if (result instanceof AgentStreamSession stream) {
                stream.completion().whenComplete((ignored, failure) -> lease.close());
                transferred = true;
            }
            return result;
        } finally {
            depth.remove();
            if (!transferred) lease.close();
        }
    }

    /** 仅标记真实 SDK 实例访问；重置协调不创建或关闭不明生命周期的后台任务。 */
    @Around("execution(public * butvan.agent.agents.agent.AgentFactory.currentAgent(..)) || "
            + "execution(public * butvan.agent.agents.subagent.SubagentTaskService.list(..)) || "
            + "execution(public * butvan.agent.agents.subagent.SubagentTaskService.get(..)) || "
            + "execution(public * butvan.agent.agents.subagent.SubagentTaskService.cancel(..))")
    public Object runtime(ProceedingJoinPoint call) throws Throwable {
        barrier.markRuntimeUsed();
        return call.proceed();
    }
}
