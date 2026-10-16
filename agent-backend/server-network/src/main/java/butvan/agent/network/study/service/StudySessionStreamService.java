package butvan.agent.network.study.service;

import butvan.agent.network.study.dto.StudySessionStreamEvent;
import butvan.agent.network.study.event.StudySessionChangedEvent;
import butvan.agent.network.study.model.StudyModels.StudySession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理用户级学习状态 SSE 订阅，并在事务提交后广播权威活动时段快照。
 *
 * <p>订阅注册、初始快照和变更广播通过同一监视器串行化，保证单条连接收到的
 * 首个事件一定是快照，且不会被更旧状态覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudySessionStreamService {
    private final StudyService studyService;
    private final ConcurrentMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    private final String streamId = UUID.randomUUID().toString();
    private long revision;
    private boolean closing;

    /** 建立用户级订阅，并把当前活动时段作为首个快照发送。 */
    public SseEmitter subscribe(String ownerId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> unsubscribe(ownerId, emitter));
        emitter.onTimeout(() -> unsubscribe(ownerId, emitter));
        emitter.onError(ignored -> unsubscribe(ownerId, emitter));
        synchronized (lifecycleMonitor) {
            if (closing) {
                emitter.complete();
                return emitter;
            }
            subscribers.computeIfAbsent(ownerId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
            try {
                StudySession activeSession = studyService.getActive(ownerId);
                send(ownerId, emitter, "snapshot",
                        new StudySessionStreamEvent(streamId, revision, "SNAPSHOT", null, activeSession));
            } catch (RuntimeException exception) {
                unsubscribe(ownerId, emitter);
                emitter.completeWithError(exception);
                throw exception;
            }
        }
        return emitter;
    }

    /** 事务成功提交后，将最新权威状态广播给同一用户的所有连接。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStudySessionChanged(StudySessionChangedEvent event) {
        try {
            synchronized (lifecycleMonitor) {
                if (closing) return;
                Set<SseEmitter> ownerSubscribers = subscribers.get(event.ownerId());
                if (ownerSubscribers == null || ownerSubscribers.isEmpty()) return;
                StudySession activeSession = studyService.getActive(event.ownerId());
                StudySessionStreamEvent payload = new StudySessionStreamEvent(
                        streamId, ++revision, event.changeType().name(), event.sessionId(), activeSession);
                new ArrayList<>(ownerSubscribers)
                        .forEach(emitter -> send(event.ownerId(), emitter, "changed", payload));
            }
        } catch (RuntimeException exception) {
            // 数据已提交，实时通知失败不能把成功写入伪装成接口失败；重连快照会恢复权威状态。
            log.error("学习状态已提交但实时通知失败：ownerId={}, sessionId={}",
                    event.ownerId(), event.sessionId(), exception);
        }
    }

    /** 在应用退出前主动结束无限超时的 SSE，避免阻塞 WebServer 优雅停机。 */
    @EventListener(ContextClosedEvent.class)
    public void onApplicationContextClosed() {
        List<SseEmitter> emitters;
        synchronized (lifecycleMonitor) {
            closing = true;
            emitters = subscribers.values().stream().flatMap(Set::stream).toList();
            subscribers.clear();
        }
        emitters.forEach(SseEmitter::complete);
        if (!emitters.isEmpty()) {
            log.info("应用关闭前已结束 {} 个学习状态 SSE 订阅", emitters.size());
        }
    }

    private void send(String ownerId, SseEmitter emitter, String eventName, StudySessionStreamEvent payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException | IllegalStateException exception) {
            log.debug("学习状态 SSE 客户端已断开：ownerId={}", ownerId, exception);
            unsubscribe(ownerId, emitter);
            emitter.complete();
        }
    }

    private void unsubscribe(String ownerId, SseEmitter emitter) {
        Set<SseEmitter> ownerSubscribers = subscribers.get(ownerId);
        if (ownerSubscribers == null) return;
        ownerSubscribers.remove(emitter);
        if (ownerSubscribers.isEmpty()) subscribers.remove(ownerId, ownerSubscribers);
    }

    int subscriberCount() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }
}
