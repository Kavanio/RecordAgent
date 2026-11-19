package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.context.ContextEnvelope;
import butvan.agent.agents.context.ContextRequest;
import butvan.agent.agents.context.ConversationContextAssembler;
import butvan.agent.agents.material.ChatMaterialRecorder;
import butvan.agent.agents.material.ReplyImages;
import butvan.agent.agents.memory.MemorySourcePolicy;
import butvan.agent.agents.memory.MemorySourceRegistry;
import butvan.agent.agents.memory.MemoryTurnLifecycle;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.review.UserTurnActivityListener;
import butvan.agent.agents.routing.ToolCapabilityRouter;
import butvan.agent.agents.routing.ToolRoutingDecision;
import butvan.agent.agents.routing.ToolRoutingRequest;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionChannel;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 单轮对话启动前的准备器。
 *
 * <p>负责输入校验、渠道隔离校验、用户消息与图片落盘、检查点保存，
 * 并通过虚拟线程并行执行上下文组装与 Jev 工具路由，减少主模型首字响应前的串行等待。</p>
 */
@Slf4j
@RequiredArgsConstructor
class AgentTurnPreparation {

    record PreparedTurn(AgentRun run, String input, boolean cancelled) {
        static PreparedTurn cancelled(AgentRun run) {
            return new PreparedTurn(run, null, true);
        }

        static PreparedTurn ready(AgentRun run, String input) {
            return new PreparedTurn(run, input, false);
        }
    }

    private final ModelHolder modelHolder;
    private final SessionCatalogService sessionCatalogService;
    private final TranscriptService transcriptService;
    private final AgentRunCheckpointService checkpointService;
    private final ConversationContextAssembler contextAssembler;
    private final ToolCapabilityRouter toolCapabilityRouter;
    private final ToolCapabilityCatalog toolCapabilityCatalog;
    private final AgentRuntimeAdapter runtimeAdapter;
    private final AgentStreamExecutor streamExecutor;
    private final Supplier<MemoryTurnLifecycle> memoryLifecycleSupplier;
    private final Supplier<UserTurnActivityListener> activityListenerSupplier;
    private final Supplier<ChatMaterialRecorder> materialRecorderSupplier;

    /** 在创建后台虚拟线程之前执行的同步入口校验。 */
    void validateIncomingCall(AgentUserCall request) {
        if (request == null) throw new IllegalArgumentException("聊天请求不能为空");
        if (!request.images().isEmpty() && !modelHolder.supportsVision()) {
            throw new IllegalArgumentException("当前模型未标注图片识别能力，请在模型配置中开启或切换到视觉模型");
        }
        if (request.images().size() > AgentImages.MAX_COUNT
                || request.images().stream().mapToLong(AgentImageAttachment::sizeBytes).sum() > AgentImages.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("每轮最多 4 张图片，总大小不能超过 12 MiB");
        }
    }

    /** 准备单轮运行上下文；若提前收到取消信号则返回 {@code cancelled = true}。 */
    PreparedTurn prepare(
            AgentUserCall request,
            String userId,
            AgentStreamSession streamSession,
            java.util.function.Consumer<AgentRun> runConsumer
    ) {
        if (request == null) throw new IllegalArgumentException("聊天请求不能为空");

        var currentSession = sessionCatalogService.requireActive(request.sessionId());
        boolean feishuSession = sessionCatalogService.getChannel(request.sessionId()) == SessionChannel.FEISHU;
        boolean feishuCall = request.memoryChannel() == MemorySourceRegistry.Channel.FEISHU;
        if (feishuSession != feishuCall) {
            throw new IllegalArgumentException("会话渠道不匹配，请在原渠道继续对话");
        }

        String requestedContext = request.context() == null || request.context().isBlank()
                ? request.content() : request.context();
        String input = requireContent(requestedContext);
        String displayContent = request.content() == null || request.content().isBlank()
                ? input : request.content().strip();

        String resolvedTurnId = resolveTurnId(request);
        List<String> imageFileIds = persistUserImages(request, userId, currentSession, resolvedTurnId);
        String turnId = transcriptService.appendUserMessageWithTurnId(
                request.sessionId(), displayContent, resolvedTurnId, imageFileIds);
        notifyUserTurnHooks(request, userId, turnId);

        RuntimeContext context = runtimeAdapter.createRuntimeContext(userId, request.sessionId());
        configureReplyImages(context, request.sessionId(), userId, turnId, currentSession, feishuSession);
        AgentRun run = new AgentRun(request.sessionId(), userId, turnId, context);
        runConsumer.accept(run);
        checkpointService.save(run);

        boolean remoteEligible = currentSession != null
                && currentSession.kind() == SessionKind.GENERAL
                && (currentSession.projectId() == null || currentSession.projectId().isBlank())
                && MemorySourcePolicy.eligible(request);
        Set<String> availableGroups = availableToolGroups(feishuSession);

        ParallelPrepResult prepResult = runParallelPreparation(
                userId, displayContent,
                remoteEligible ? request.memoryChannel() : null,
                availableGroups, streamSession
        );
        context.put(ContextEnvelope.class, prepResult.contextEnvelope());

        // 仅新用户轮次修复进程重启遗留调用；HITL 恢复必须让 AgentScope 原样消费 ConfirmResult。
        runtimeAdapter.recoverOrphanedToolCalls(userId, request.sessionId());
        if (streamSession.isCancelled()) {
            return PreparedTurn.cancelled(run);
        }

        ToolRoutingDecision routingDecision = prepResult.routingDecision();
        context.put(ToolRoutingDecision.class, routingDecision);
        emitRoutingNotice(routingDecision, streamSession);
        runtimeAdapter.synchronizeToolRoutingState(userId, request.sessionId(), routingDecision);
        if (streamSession.isCancelled()) {
            return PreparedTurn.cancelled(run);
        }

        return PreparedTurn.ready(run, input);
    }

    private record ParallelPrepResult(ContextEnvelope contextEnvelope, ToolRoutingDecision routingDecision) {}

    private ParallelPrepResult runParallelPreparation(
            String userId,
            String displayContent,
            MemorySourceRegistry.Channel recallChannel,
            Set<String> availableGroups,
            AgentStreamSession streamSession
    ) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ContextEnvelope> contextFuture = CompletableFuture.supplyAsync(
                    () -> contextAssembler.assemble(new ContextRequest(
                            userId, displayContent, recallChannel, streamSession::isCancelled)),
                    executor);
            CompletableFuture<ToolRoutingDecision> routingFuture = CompletableFuture.supplyAsync(
                    () -> toolCapabilityRouter.route(new ToolRoutingRequest(displayContent, availableGroups)),
                    executor);
            return new ParallelPrepResult(awaitFuture(contextFuture), awaitFuture(routingFuture));
        }
    }

    private <T> T awaitFuture(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("准备阶段被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("准备阶段执行失败", cause);
        }
    }

    private String resolveTurnId(AgentUserCall request) {
        String resolvedTurnId = request.turnId();
        if (resolvedTurnId != null) {
            transcriptService.validateUnusedTurnId(request.sessionId(), resolvedTurnId);
            return resolvedTurnId;
        }
        return UUID.randomUUID().toString();
    }

    private List<String> persistUserImages(
            AgentUserCall request,
            String userId,
            butvan.agent.agents.session.dto.SessionSummaryDto currentSession,
            String resolvedTurnId
    ) {
        if (request.images().isEmpty()) return List.of();
        String channel = request.memoryChannel() != null
                ? request.memoryChannel().name()
                : sessionCatalogService.getChannel(request.sessionId()).name();
        return materialRecorderSupplier.get().recordUserImages(
                new ChatMaterialRecorder.SaveImagesCommand(
                        userId,
                        channel,
                        currentSession.kind().name(),
                        request.sessionId(),
                        currentSession.projectId(),
                        currentSession.title(),
                        resolvedTurnId,
                        request.images()
                ));
    }

    private void notifyUserTurnHooks(AgentUserCall request, String userId, String turnId) {
        UserTurnActivityListener listener = activityListenerSupplier.get();
        if (listener != null) {
            try {
                listener.onUserTurnRecorded(userId, request.sessionId(), request.memoryChannel());
            } catch (RuntimeException exception) {
                log.debug("用户发言监听降级：errorType={}", exception.getClass().getSimpleName());
            }
        }
        try {
            memoryLifecycleSupplier.get().admitted(request, userId, turnId);
        } catch (RuntimeException exception) {
            log.warn("经历来源登记未完成，聊天继续：errorType={}", exception.getClass().getSimpleName());
        }
    }

    private void configureReplyImages(
            RuntimeContext context,
            String sessionId,
            String userId,
            String turnId,
            butvan.agent.agents.session.dto.SessionSummaryDto currentSession,
            boolean feishuSession
    ) {
        if (feishuSession) {
            ReplyImages.disableForChannel(context);
        } else {
            context.put(ReplyImages.class, new ReplyImages(
                    new ChatMaterialRecorder.SaveImagesCommand(
                            userId, "DESKTOP", currentSession.kind().name(), sessionId,
                            currentSession.projectId(), currentSession.title(), turnId, List.of())));
        }
    }

    private Set<String> availableToolGroups(boolean feishuSession) {
        if (!feishuSession) return toolCapabilityCatalog.groupNames();
        return toolCapabilityCatalog.groupNames().stream()
                .filter(group -> !"screen".equals(group))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Jev 降级时发送非终态提示，使聊天继续执行且桌面端可以告知用户。 */
    private void emitRoutingNotice(ToolRoutingDecision decision, AgentStreamSession streamSession) {
        if (decision == null
                || decision.status() != ToolRoutingDecision.Status.FALLBACK
                || decision.failure() == null) {
            return;
        }
        var failure = decision.failure();
        streamExecutor.putEvent(streamSession, new AgentStreamEvent.RoutingNotice(
                failure.code().payloadValue(),
                failure.message(),
                failure.requestId(),
                failure.retryable(),
                failure.retryAfterMillis()
        ));
    }

    /** 校验用户输入，避免空消息和异常大的请求进入模型。 */
    String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String normalized = content.strip();
        if (normalized.length() > 20_000) {
            throw new IllegalArgumentException("消息内容不能超过 20000 个字符");
        }
        return normalized;
    }
}
