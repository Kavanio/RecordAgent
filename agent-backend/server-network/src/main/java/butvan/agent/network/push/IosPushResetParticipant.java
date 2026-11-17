package butvan.agent.network.push;

import butvan.agent.agents.reset.ResetParticipant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 恢复出厂设置时清理待送达的 iOS 主动通知队列；保留 APNs 服务端凭据与设备连接配置。
 */
@Component
@RequiredArgsConstructor
public class IosPushResetParticipant implements ResetParticipant {
    private final IosPushStore store;
    private final IosPushStreamService streamService;

    @Override
    public String id() {
        return "ios-push";
    }

    @Override
    public String scopeVersion(String owner) {
        return "ios-push-v1";
    }

    @Override
    public List<String> additionalDeletes(String owner) {
        return List.of("iOS 主动通知待投递队列（保留 APNs 凭据与设备登记）");
    }

    @Override
    public void clean(String owner) {
        store.clearNoticesForOwner(owner);
        streamService.publish(owner, null);
    }
}
