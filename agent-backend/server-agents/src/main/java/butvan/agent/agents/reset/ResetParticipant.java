package butvan.agent.agents.reset;

import java.util.List;

/** 小型重置参与方：先只读校验，再停稳，最后执行可重复的领域清理；不承载通用工作流。 */
public interface ResetParticipant {
    String id();
    default void preflight(String owner) throws Exception { }
    default void stop(String owner) throws Exception { }
    void clean(String owner) throws Exception;
    default void resume() { }
    /** 范围或接收方改变时令旧预览失效；空值表示原本地参与方。 */
    default String scopeVersion(String owner) { return ""; }
    default List<String> additionalDeletes(String owner) { return List.of(); }
    /** 无远端责任的参与方保持原 M1 语义。 */
    default String remoteStatus(String owner, String resetId) { return "NOT_INTEGRATED"; }
}
