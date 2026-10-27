package butvan.agent.feishu.service;

import butvan.agent.feishu.config.FeishuConfigData;
import butvan.agent.feishu.config.FeishuProperties;
import com.lark.oapi.Client;
import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.LarkChannelFactory;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.ChannelErrorEvent;
import com.lark.oapi.channel.model.NormalizedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.function.Function;

/** 唯一长连接所有者；后台建连、有界等待与退避，停机和迟到连接都必须释放资源。 */
@Slf4j
@Component
public class FeishuChannelLifecycle implements SmartLifecycle {
    private final FeishuProperties properties;
    private final FeishuChatService chat;
    private final Function<FeishuConfigData, LarkChannel> factory;
    private final long connectTimeoutMillis;
    private volatile LarkChannel channel;
    private volatile boolean running;
    private volatile long generation;
    private Thread worker;

    @Autowired
    public FeishuChannelLifecycle(FeishuProperties properties, FeishuChatService chat) {
        this(properties, chat, FeishuChannelLifecycle::buildChannel, 30_000);
    }

    FeishuChannelLifecycle(FeishuProperties properties, FeishuChatService chat,
                           Function<FeishuConfigData, LarkChannel> factory, long connectTimeoutMillis) {
        this.properties = properties; this.chat = chat; this.factory = factory;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public synchronized void start() {
        if (running || (worker != null && worker.isAlive())) return;
        FeishuConfigData config = properties.load();
        if (!config.isReady()) {
            log.info("飞书渠道未启用：需要 enabled、App ID、App Secret 以及唯一的本人 allowedSenderIds；修改后重启。");
            return;
        }
        running = true;
        long currentGeneration = ++generation;
        worker = Thread.ofVirtual().name("feishu-connect").unstarted(() -> connectLoop(config, currentGeneration));
        worker.start();
    }

    private void connectLoop(FeishuConfigData config, long currentGeneration) {
        try {
            if (!active(currentGeneration)) return;
            Client client = Client.newBuilder(config.appId(), config.appSecret())
                    .requestTimeout(15, TimeUnit.SECONDS).logReqAtDebug(false).build();
            chat.open(config, new LarkMessageSender(client), new LarkImageFetcher(client));
            long delayMillis = 1000;
            while (active(currentGeneration)) {
                LarkChannel candidate = null;
                try {
                    candidate = factory.apply(config);
                    LarkChannel built = candidate;
                    synchronized (this) {
                        if (!active(currentGeneration)) { disconnect(built); return; }
                        channel = built;
                    }
                    built.on("message", (NormalizedMessage message) -> {
                        if (active(currentGeneration) && channel == built) chat.handle(message);
                    });
                    built.on("error", (ChannelErrorEvent event) ->
                            log.warn("飞书渠道事件异常：event={}", event.getEventName()));
                    built.on("reconnecting", (Object ignored) -> log.warn("飞书连接正在重连"));
                    built.on("reconnected", (Object ignored) -> log.info("飞书连接已恢复"));
                    CompletableFuture<?> connection = built.connect();
                    connection.whenComplete((identity, error) -> {
                        // get 超时或 stop 后，SDK 的底层异步连接仍可能迟到成功，必须再关闭一次。
                        if (!active(currentGeneration) || channel != built) disconnect(built);
                    });
                    connection.get(connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    if (!active(currentGeneration)) return;
                    log.info("飞书连接已建立，已启用本人私聊白名单及非卡片文本回复");
                    // 首次成功后由 SDK 管理断线重连，不再创建第二条连接。
                    while (active(currentGeneration)) Thread.sleep(1000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); return;
                } catch (Exception exception) {
                    log.warn("飞书建连失败，将退避重试：errorType={}", exception.getClass().getSimpleName());
                } finally {
                    if (channel == candidate) channel = null;
                    disconnect(candidate);
                }
                if (active(currentGeneration)) {
                    Thread.sleep(delayMillis);
                    delayMillis = Math.min(60_000, delayMillis * 2);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.error("飞书渠道初始化失败，保持停用：errorType={}", exception.getClass().getSimpleName());
        } finally {
            if (generation == currentGeneration) running = false;
            chat.close();
        }
    }

    static LarkChannel buildChannel(FeishuConfigData config) {
        LarkChannelOptions.SafetyConfig safety = new LarkChannelOptions.SafetyConfig();
        // 应用层受理每条原始消息；关闭 SDK 默认 600ms 合并，避免消息和发送者被折叠。
        safety.setChatQueueEnabled(false);
        safety.getBatchText().setDelayMs(0);
        safety.getBatchText().setLongDelayMs(0);
        safety.getBatchText().setMaxMessages(1);
        LarkChannelOptions.PolicyConfig policy = new LarkChannelOptions.PolicyConfig();
        policy.setDmMode("allowlist");
        policy.setDmAllowlist(config.allowedSenderIds());
        return LarkChannelFactory.createLarkChannel(LarkChannelOptions.newBuilder(config.appId(), config.appSecret())
                .transport("websocket").safety(safety).policy(policy).includeRawEvent(false).build());
    }

    private boolean active(long currentGeneration) { return running && generation == currentGeneration; }

    private static void disconnect(LarkChannel channel) {
        if (channel == null) return;
        try { channel.disconnect().get(5, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        catch (Exception exception) { log.warn("飞书连接关闭未确认：errorType={}", exception.getClass().getSimpleName()); }
    }

    @Override
    public void stop() {
        Thread currentWorker;
        LarkChannel currentChannel;
        synchronized (this) {
            running = false; generation++;
            currentWorker = worker; currentChannel = channel; channel = null;
        }
        if (currentWorker != null) currentWorker.interrupt();
        chat.close();
        disconnect(currentChannel);
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
