package butvan.agent.agents.reset;

/** 小型重置参与方：先只读校验，再停稳，最后执行可重复的领域清理；不承载通用工作流。 */
public interface ResetParticipant {
    String id();
    default void preflight(String owner) throws Exception { }
    default void stop(String owner) throws Exception { }
    void clean(String owner) throws Exception;
    default void resume() { }
}
