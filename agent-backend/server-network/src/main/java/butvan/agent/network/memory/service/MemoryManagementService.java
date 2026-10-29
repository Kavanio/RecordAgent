package butvan.agent.network.memory.service;

import butvan.agent.agents.memory.MemorySourcePolicy;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.dto.MemoryDtos.*;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.repository.MemoryManagementRepository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** 当前用户的来源管理；先提交本地失效，再以原任务显式核对删除，不重放保存。 */
@Slf4j
@Service
public class MemoryManagementService {
    private final MemoryService memory;
    private final MemoryManagementRepository repository;
    private final MemorySyncRepository sources;
    private final MemorySourceVerifier verifier;
    private final MemoryRemoteGateway remote;
    private final ResetBarrier barrier;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MemoryManagementService(MemoryService memory, MemoryManagementRepository repository, MemorySyncRepository sources,
                                   MemorySourceVerifier verifier, MemoryRemoteGateway remote, ResetBarrier barrier,
                                   PlatformTransactionManager manager, Clock automationClock) {
        this.memory = memory; this.repository = repository; this.sources = sources; this.verifier = verifier;
        this.remote = remote; this.barrier = barrier; this.transaction = new TransactionTemplate(manager); this.clock = automationClock;
    }

    /** 有界分页只提供处理状态，避免在列表展开全部聊天。 */
    public DocumentPage documents(String cursor, int limit) {
        if (cursor == null || !cursor.matches("[0-9]{1,18}") || limit < 1 || limit > 50)
            throw new MemoryRequestException(400, "来源游标或每页数量无效（1—50）");
        return guarded(() -> {
            var rows = repository.documents(space(), Long.parseLong(cursor), limit + 1);
            var page = rows.stream().limit(limit).toList();
            return new DocumentPage(page.stream().map(this::response).toList(), rows.size() > limit ? Long.toString(page.getLast().cursor()) : null);
        });
    }

    /** 原文仅通过既有权威核对按需读取，缺失与远端状态分开显示。 */
    public DocumentDetail document(String id) {
        identifier(id);
        return guarded(() -> {
            String space = space();
            var document = repository.document(space, id);
            var original = sources.sourceForManagement(space, id).map(verifier::read).orElse(null);
            return new DocumentDetail(response(document), original == null ? "UNAVAILABLE" : "AVAILABLE",
                    original == null ? null : original.user().content(), document.correction(), document.correction() == null ? "NONE" : "LOCAL_ONLY");
        });
    }

    /** 用户纠正独立保存，当前用途授权不覆盖纠正外发；旧来源立即失效。 */
    public OperationResponse correct(String id, Correct input) {
        if (input.correctedText() == null || !MemorySourcePolicy.eligibleText(input.correctedText()))
            throw new MemoryRequestException(400, "纠正须为 1—4000 字符的普通文本，请勿填写代码、密钥或敏感内容");
        return mutate(id, input.expectedVersion(), input.confirmed(), input.requestId(), "CORRECT", input.correctedText());
    }

    /** 删除仅撤回记忆来源和本域纠正，不修改原始聊天或业务记录。 */
    public OperationResponse delete(String id, DeleteDocument input) {
        return mutate(id, input.expectedVersion(), input.confirmed(), input.requestId(), "DELETE", "");
    }

    private OperationResponse mutate(String id, long version, boolean confirmed, String request, String kind, String text) {
        identifier(id);
        if (version < 0 || !confirmed || request == null || !request.matches("[a-zA-Z0-9_-]{1,80}"))
            throw new MemoryRequestException(400, "请确认操作、来源版本和有效请求标识");
        String hash = MemorySourcePolicy.hash(id + "\n" + version + "\n" + kind + "\n" + text);
        return guarded(() -> {
            String space = space();
            return transaction.execute(status -> {
                var previous = repository.command(space, request);
                if (previous.isPresent()) {
                    if (!previous.get().hash().equals(hash)) throw new MemoryRequestException(409, "请求 ID 已用于另一项来源操作");
                    return response(space, previous.get());
                }
                repository.document(space, id);
                return response(space, repository.mutate(space, id, version, kind, text, request, hash, clock.instant()));
            });
        });
    }

    /** 页面刷新只读原任务，查询不自动触发外部删除。 */
    public OperationResponse operation(String id) {
        identifier(id);
        return guarded(() -> {
            String space = space();
            return response(space, repository.operation(space, id));
        });
    }

    /** 显式继续同一删除核对；与在途保存互斥，网络期间不持有数据库事务。 */
    public OperationResponse retry(String id) {
        identifier(id);
        return guarded(() -> {
            String space = space();
            var operation = repository.operation(space, id);
            if (operation.state().equals("SUCCEEDED")) return response(space, operation);
            var lock = memory.sourceOperationLock(operation.sourceId());
            // 同线程假 Adapter 回调也不能越过一个尚未结束的生产者。
            if (lock.isHeldByCurrentThread() || !lock.tryLock()) return update(space, operation, "WAITING_REMOTE");
            try {
                var target = repository.target(space, operation.sourceId());
                if (target.isEmpty()) return update(space, operation, "SUCCEEDED");
                if (!target.get().recipientFingerprint().equals(remote.recipientFingerprint())) return update(space, operation, "UNKNOWN");
                String state;
                try {
                    var result = remote.deleteAndVerify(target.get());
                    state = result == null ? "UNKNOWN" : switch (result) {
                        case VERIFIED -> "SUCCEEDED";
                        case FAILED -> "FAILED";
                        case UNKNOWN -> "UNKNOWN";
                    };
                } catch (RuntimeException exception) {
                    log.warn("记忆删除核对结果未知：errorType={}", exception.getClass().getSimpleName());
                    state = "UNKNOWN";
                }
                return update(space, operation, state);
            } finally { lock.unlock(); }
        });
    }

    private OperationResponse update(String space, Operation operation, String state) {
        return transaction.execute(status -> {
            repository.status(space, operation, state, clock.instant());
            return response(space, repository.operation(space, operation.id()));
        });
    }
    private String space() { return memory.settings().spaceId(); }
    private <T> T guarded(Supplier<T> work) {
        try (var ignored = barrier.enter()) { return work.get(); }
    }
    private void identifier(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,128}")) throw new MemoryRequestException(400, "来源或任务标识无效");
    }
    private DocumentResponse response(Document d) {
        return new DocumentResponse(d.id(), d.version(), d.occurredAt(), d.channel(), d.state(), d.syncState(),
                d.deletionState(), d.correction() != null, d.operationId(), progress(d.cleanup()));
    }
    private OperationResponse response(String space, Operation o) {
        var cleanup = repository.document(space, o.sourceId()).cleanup();
        return new OperationResponse(o.id(), o.sourceId(), o.kind(), o.version(), o.state(), o.updatedAt(),
                !o.state().equals("SUCCEEDED"), progress(cleanup));
    }
    /** 只投影已记录步骤；不联网、不据此把 UNKNOWN 升级为成功或解除召回／重置屏障。 */
    private CleanupProgress progress(CleanupSnapshot snapshot) {
        return snapshot == null ? null : new CleanupProgress(snapshot.documentDeleted(), snapshot.factsChecked(),
                snapshot.knownChildren(), snapshot.deletedChildren(), snapshot.parentDeleted());
    }
}
