package butvan.agent.agents.reset;

/** 重置期间拒绝新个人上下文操作，保持独立且稳定的业务语义。 */
public class ResetBlockedException extends IllegalStateException {
    public ResetBlockedException() { super("Agent 正在恢复出厂设置，请在设置中查看进度"); }
}
