package butvan.agent.agents.context;

/**
 * 单轮上下文组装请求。
 *
 * @param userId       工作区用户命名空间
 * @param query        当前用户意图，用于 Memory 相关性排序
 * @param totalBudget  自动上下文总 Token 预算
 * @param profileBudget Profile 上限
 * @param memoryBudget Memory 召回上限
 * @param memoryTopK   最多返回的 Memory 片段数
 */
public record ContextRequest(
        String userId,
        String query,
        int totalBudget,
        int profileBudget,
        int memoryBudget,
        int memoryTopK,
        butvan.agent.agents.memory.MemorySourceRegistry.Channel channel,
        java.util.function.BooleanSupplier cancelled
) {

    public static final int DEFAULT_TOTAL_BUDGET = 850;
    public static final int DEFAULT_PROFILE_BUDGET = 300;
    public static final int DEFAULT_MEMORY_BUDGET = 500;
    public static final int DEFAULT_MEMORY_TOP_K = 4;

    public ContextRequest {
        if (userId == null || !userId.matches("[a-zA-Z0-9-]+")) {
            throw new IllegalArgumentException("上下文用户 ID 格式非法");
        }
        query = query == null ? "" : query.strip();
        if (totalBudget < 0 || profileBudget < 0 || memoryBudget < 0 || memoryTopK < 0) {
            throw new IllegalArgumentException("上下文预算不能为负数");
        }
        totalBudget = Math.min(totalBudget, DEFAULT_TOTAL_BUDGET);
        profileBudget = Math.min(profileBudget, DEFAULT_PROFILE_BUDGET);
        memoryBudget = Math.min(memoryBudget, DEFAULT_MEMORY_BUDGET);
        memoryTopK = Math.min(memoryTopK, DEFAULT_MEMORY_TOP_K);
        cancelled = cancelled == null ? () -> false : cancelled;
    }

    /** 旧调用只使用本地检索，不从缺少可信来源的参数推测远端外发许可。 */
    public ContextRequest(String userId, String query, int totalBudget, int profileBudget, int memoryBudget, int memoryTopK) {
        this(userId, query, totalBudget, profileBudget, memoryBudget, memoryTopK, null, () -> false);
    }
    public ContextRequest(String userId, String query, butvan.agent.agents.memory.MemorySourceRegistry.Channel channel,
                          java.util.function.BooleanSupplier cancelled) {
        this(userId, query, DEFAULT_TOTAL_BUDGET, DEFAULT_PROFILE_BUDGET, DEFAULT_MEMORY_BUDGET, DEFAULT_MEMORY_TOP_K, channel, cancelled);
    }

    public ContextRequest(String userId, String query) {
        this(userId, query, DEFAULT_TOTAL_BUDGET, DEFAULT_PROFILE_BUDGET,
                DEFAULT_MEMORY_BUDGET, DEFAULT_MEMORY_TOP_K);
    }
}
