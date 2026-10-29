package butvan.agent.agents.prompts;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;

/** 缓存 Agent 跨日复用时刷新环境日期；历史事件日期仍只能取其权威记录时间。 */
public class RuntimeDateMiddleware implements MiddlewareBase {
    private final Clock clock;

    public RuntimeDateMiddleware() { this(Clock.systemDefaultZone()); }
    RuntimeDateMiddleware(Clock clock) { this.clock = clock; }

    @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.just(prompt.replaceAll("(?m)^ - 当前日期: .*?$",
                " - 当前日期: " + LocalDate.now(clock) + "（" + clock.getZone() + "）"));
    }
}
