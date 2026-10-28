package butvan.agent.agents.context;

/** 一段已通过预算裁剪、可追溯来源的上下文。 */
public record ContextBlock(ContextKind kind, String source, String content, int estimatedTokens,
                           java.util.function.BooleanSupplier stillCurrent) {

    public ContextBlock(ContextKind kind, String source, String content, int estimatedTokens) {
        this(kind, source, content, estimatedTokens, () -> true);
    }

    public ContextBlock {
        if (kind == null) throw new IllegalArgumentException("上下文类别不能为空");
        source = source == null ? "unknown" : source;
        content = content == null ? "" : content.strip();
        estimatedTokens = Math.max(0, estimatedTokens);
        stillCurrent = stillCurrent == null ? () -> false : stillCurrent;
    }
}
