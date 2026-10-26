package butvan.agent.agents.agent.run;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.usage.ModelIdentity;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 最终快照必须修复增量尾部缺失，不能重复追加或混入工具前的说明。 */
class AgentRunFinalReplyTest {

    @Test
    void finalSnapshotReplacesIntermediateAndIncompleteDeltas() {
        AgentRun run = new AgentRun("session", "owner", "turn", null);
        run.record(new AgentStreamEvent.TextDelta("我先查询资料。"));
        run.record(new AgentStreamEvent.TextDelta("最终答案还没输"));
        run.recordModelEvent(new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT).textContent("最终答案已经完整输出。🙂\n").build()),
                new ModelIdentity("test", "model"));
        assertEquals("最终答案已经完整输出。🙂\n", run.contentAsString());
    }

    @Test
    void childFinalResultCannotReplaceMainReply() {
        AgentRun run = new AgentRun("session", "owner", "turn", null);
        run.record(new AgentStreamEvent.TextDelta("主 Agent 的正文"));
        run.recordModelEvent(new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT).textContent("子任务结果").build())
                .withSource("main/explore"), new ModelIdentity("test", "model"));
        assertEquals("主 Agent 的正文", run.contentAsString());
    }

    @Test
    void finalSnapshotIsNotAppendedTwice() {
        AgentRun run = new AgentRun("session", "owner", "turn", null);
        run.record(new AgentStreamEvent.TextDelta("完整答案"));
        AgentResultEvent result = new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT).textContent("完整答案").build());
        run.recordModelEvent(result, new ModelIdentity("test", "model"));
        run.recordModelEvent(result, new ModelIdentity("test", "model"));
        assertEquals("完整答案", run.contentAsString());
    }
}
