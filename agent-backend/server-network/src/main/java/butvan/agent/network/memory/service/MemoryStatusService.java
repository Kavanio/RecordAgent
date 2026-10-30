package butvan.agent.network.memory.service;

import butvan.agent.network.memory.dto.MemoryDtos.RuntimePolicyResponse;
import butvan.agent.network.memory.dto.MemoryDtos.StatusResponse;
import butvan.agent.network.memory.dto.MemoryDtos.UsageResponse;
import butvan.agent.network.memory.model.MemoryModels.RuntimePolicy;
import butvan.agent.network.memory.repository.MemoryRecallRepository;
import butvan.agent.network.memory.repository.MemoryStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 运行状态只聚合本地权威账本；不读取正文、不探测远端、不把入队当已形成记忆。 */
@Service
@RequiredArgsConstructor
public class MemoryStatusService {
    private final MemoryService memory;
    private final MemoryStatusRepository repository;
    private final MemoryRecallRepository recall;
    private final Clock clock;

    /** 返回当前空间的覆盖、积压、暂停原因与当日分项计数。 */
    public StatusResponse status() {
        var state = memory.state();
        var grant = state.grant();
        String space = state.settings().spaceId();
        var limits = memory.limits(grant);
        String day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        var usage = repository.usage(space, day);
        var pauses = pauseReasons(state.authorized(), state.settings().autoSave(), state.settings().autoRecall(),
                space, limits.legacyQueryMeter(), limits.writeLimit(), limits.statusLimit(), limits.recallLimit(), usage);
        return new StatusResponse(
                repository.sources(space, "CAPTURED") + repository.sources(space, "REVOKED"),
                repository.sources(space, "CAPTURED"),
                repository.operations(space),
                repository.oldestPending(space),
                List.copyOf(pauses),
                new UsageResponse(day, usage.writeCalls(), usage.legacyCombinedQueries(), usage.statusCalls(), usage.recallCalls(),
                        limits.writeLimit(), limits.statusLimit(), limits.recallLimit()),
                repository.skipped(space, day));
    }

    /** 运行保护快照；修改入口在 MemoryService，同样受重置代次与租约约束。 */
    public RuntimePolicyResponse runtimePolicy() {
        RuntimePolicy policy = memory.runtimePolicy();
        return new RuntimePolicyResponse(policy.version(), policy.batchSize(), policy.dailyWriteLimit(),
                policy.dailyStatusLimit(), policy.dailyRecallLimit());
    }

    /** V1 只有合并查询上限，不拆成推测性的召回／核对比例；单条来源删除不再连坐暂停整个空间的召回。 */
    private List<String> pauseReasons(boolean authorized, boolean autoSave, boolean autoRecall, String space,
                                      boolean legacyMeter, int writeLimit, int statusLimit, int recallLimit,
                                      MemoryStatusRepository.Usage usage) {
        var pauses = new ArrayList<String>();
        if (!authorized) { pauses.add("NOT_AUTHORIZED"); return pauses; }
        if (!autoSave) pauses.add("SAVE_DISABLED");
        if (!autoRecall) pauses.add("RECALL_DISABLED");
        if (legacyMeter) {
            if (usage.legacyCombinedQueries() >= recallLimit) pauses.add("LEGACY_QUERY_BUDGET_EXHAUSTED");
            return pauses;
        }
        if (usage.writeCalls() >= writeLimit) pauses.add("WRITE_BUDGET_EXHAUSTED");
        if (usage.statusCalls() >= statusLimit) pauses.add("STATUS_BUDGET_EXHAUSTED");
        if (usage.recallCalls() >= recallLimit) pauses.add("RECALL_BUDGET_EXHAUSTED");
        return pauses;
    }
}
