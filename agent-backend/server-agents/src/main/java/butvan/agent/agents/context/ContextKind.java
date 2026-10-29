package butvan.agent.agents.context;

/** 自动组装上下文的来源类别。 */
public enum ContextKind {
    PROFILE,
    MEMORY,
    /** 管线控制状态，不是召回的个人经历，单独归因。 */
    CONTROL
}
