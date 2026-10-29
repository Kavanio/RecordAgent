package butvan.agent.agents.context;

import java.util.List;

/** 一次组装的只读上下文结果，由中间件临时注入 Model Call。 */
public record ContextEnvelope(String rendered, List<ContextBlock> blocks, int estimatedTokens,
                              java.util.function.BooleanSupplier stillCurrent) {

    public ContextEnvelope(String rendered, List<ContextBlock> blocks, int estimatedTokens) {
        this(rendered, blocks, estimatedTokens, () -> true);
    }

    public ContextEnvelope {
        rendered = rendered == null ? "" : rendered.strip();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        estimatedTokens = Math.max(0, estimatedTokens);
        stillCurrent = stillCurrent == null ? () -> false : stillCurrent;
    }

    public static ContextEnvelope empty() {
        return new ContextEnvelope("", List.of(), 0);
    }

    public boolean isEmpty() {
        return rendered.isBlank();
    }

    /** 画像正文的本地估算 Token，不包含信封标签等协议开销。 */
    public int profileTokens() {
        return blocks.stream().filter(block -> block.kind() == ContextKind.PROFILE)
                .mapToInt(ContextBlock::estimatedTokens).sum();
    }

    /** 召回记忆正文的本地估算 Token，不包含信封标签等协议开销。 */
    public int memoryTokens() {
        return blocks.stream().filter(block -> block.kind() == ContextKind.MEMORY)
                .mapToInt(ContextBlock::estimatedTokens).sum();
    }

}
