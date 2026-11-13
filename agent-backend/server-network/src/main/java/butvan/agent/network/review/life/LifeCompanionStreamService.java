package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeCompanionModels.CompanionView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主动陪伴状态与桌面问候通知实时 SSE 推流服务（替代客户端定时轮询）。
 */
@Slf4j
@Service
public class LifeCompanionStreamService {
    private final ConcurrentMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Object monitor = new Object();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "companion-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private boolean closing;

    public LifeCompanionStreamService() {
        heartbeat.scheduleWithFixedDelay(this::sendHeartbeats, 25, 25, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String ownerId, CompanionView initialSnapshot) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> unsubscribe(ownerId, emitter));
        emitter.onTimeout(() -> unsubscribe(ownerId, emitter));
        emitter.onError(ignored -> unsubscribe(ownerId, emitter));
        synchronized (monitor) {
            if (closing) {
                emitter.complete();
                return emitter;
            }
            subscribers.computeIfAbsent(ownerId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
            if (!send(ownerId, emitter, "snapshot", initialSnapshot)) {
                return emitter;
            }
        }
        return emitter;
    }

    public void publish(String ownerId, CompanionView snapshot) {
        if (ownerId == null || snapshot == null) return;
        synchronized (monitor) {
            if (closing) return;
            Set<SseEmitter> ownerSubscribers = subscribers.get(ownerId);
            if (ownerSubscribers == null || ownerSubscribers.isEmpty()) return;
            for (SseEmitter emitter : new ArrayList<>(ownerSubscribers)) {
                send(ownerId, emitter, "updated", snapshot);
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onApplicationContextClosed() {
        heartbeat.shutdownNow();
        List<SseEmitter> emitters;
        synchronized (monitor) {
            closing = true;
            emitters = subscribers.values().stream().flatMap(Set::stream).toList();
            subscribers.clear();
        }
        emitters.forEach(SseEmitter::complete);
    }

    private boolean send(String ownerId, SseEmitter emitter, String eventName, CompanionView payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (IOException | IllegalStateException exception) {
            log.debug("主动陪伴 SSE 客户端已断开：ownerId={}", ownerId);
            unsubscribe(ownerId, emitter);
            emitter.complete();
            return false;
        }
    }

    private void sendHeartbeats() {
        synchronized (monitor) {
            if (closing || subscribers.isEmpty()) return;
            for (var entry : subscribers.entrySet()) {
                for (SseEmitter emitter : new ArrayList<>(entry.getValue())) {
                    try {
                        emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                    } catch (IOException | IllegalStateException ignored) {
                        unsubscribe(entry.getKey(), emitter);
                        emitter.complete();
                    }
                }
            }
        }
    }

    private void unsubscribe(String ownerId, SseEmitter emitter) {
        Set<SseEmitter> ownerSubscribers = subscribers.get(ownerId);
        if (ownerSubscribers == null) return;
        ownerSubscribers.remove(emitter);
        if (ownerSubscribers.isEmpty()) subscribers.remove(ownerId, ownerSubscribers);
    }
}
