package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeSignalMonitorService.MailInboxClient;
import butvan.agent.network.review.life.LifeSignalMonitorService.ServerProbeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.search.FlagTerm;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 现实信号的只读网络适配器：有界 HTTP 响应及加密、分页的 IMAP 元数据。 */
@Slf4j
final class LifeSignalClients {
    private LifeSignalClients() {}

    /** HTTP 200 才是可用；显式健康状态必须为 UP，不跟随登录跳转。 */
    static ServerProbeClient probe() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var mapper = new ObjectMapper();
        return url -> {
            CompletableFuture<HttpResponse<String>> pending = null;
            try {
                var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                        .header("User-Agent", "ButvanAgent-ServerProbe/1.0").GET().build();
                pending = client.sendAsync(request, ignored -> boundedBody());
                var response = pending.get(3, TimeUnit.SECONDS);
                int code = response.statusCode();
                if (code != 200) return new ServerProbeClient.ProbeResult(false, code, "HTTP " + code + " 状态异常");
                String body = response.body().strip();
                if (body.startsWith("{")) {
                    var json = mapper.readTree(body);
                    if (json.has("status") && !"UP".equalsIgnoreCase(json.path("status").asText()))
                        return new ServerProbeClient.ProbeResult(false, code, "健康状态非 UP");
                }
                return new ServerProbeClient.ProbeResult(true, code, "HTTP 200");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ServerProbeClient.ProbeResult(false, 0, "探测已中断");
            } catch (Exception e) {
                return new ServerProbeClient.ProbeResult(false, 0, "探测失败或超时");
            } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
        };
    }

    /** 在聚合响应前限制实际字节数；总请求等待时间另由 future 的三秒上限控制。 */
    private static HttpResponse.BodySubscriber<String> boundedBody() {
        var downstream = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long bytes;
            public CompletionStage<String> getBody() { return downstream.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; downstream.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                bytes += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
                if (bytes > 32 * 1024) {
                    subscription.cancel(); downstream.onError(new IOException("健康检查响应过大"));
                } else downstream.onNext(buffers);
            }
            public void onError(Throwable error) { downstream.onError(error); }
            public void onComplete() { downstream.onComplete(); }
        };
    }

    /** 每次只读最多二十封未读邮件的信封及 Message-ID，持续向更早未读翻页。 */
    static MailInboxClient mail() {
        return (host, port, username, password, offset) -> {
            String protocol = port == 993 ? "imaps" : "imap";
            var props = new Properties();
            String prefix = "mail." + protocol + ".";
            props.put(prefix + "connectiontimeout", "5000");
            props.put(prefix + "timeout", "5000");
            props.put(prefix + "writetimeout", "5000");
            props.put(prefix + "ssl.checkserveridentity", "true");
            if (port == 993) props.put(prefix + "ssl.enable", "true");
            else {
                props.put(prefix + "starttls.enable", "true");
                props.put(prefix + "starttls.required", "true");
            }
            Store store = null;
            Folder inbox = null;
            try {
                store = Session.getInstance(props).getStore(protocol);
                store.connect(host, port, username, password);
                inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                return readPage(inbox, offset);
            } catch (Exception e) {
                throw new IllegalStateException("邮箱连接或读取失败，请检查连接与授权码", e);
            } finally {
                if (inbox != null && inbox.isOpen()) try { inbox.close(false); }
                catch (MessagingException e) { log.warn("关闭只读邮箱失败：{}", e.getClass().getSimpleName()); }
                if (store != null && store.isConnected()) try { store.close(); }
                catch (MessagingException e) { log.warn("关闭邮箱连接失败：{}", e.getClass().getSimpleName()); }
            }
        };
    }
    /** 分页只触碰信封与指定头；不会调用 getContent 或修改已读标记。 */
    static MailInboxClient.InboxPage readPage(Folder inbox, int offset) throws MessagingException {
        Message[] unread = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        int end = unread.length - Math.min(Math.max(offset, 0), unread.length);
        if (end == 0) end = unread.length;
        int start = Math.max(0, end - 20);
        Message[] page = Arrays.copyOfRange(unread, start, end);
        var profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE); profile.add("Message-ID");
        inbox.fetch(page, profile);
        var result = new ArrayList<MailInboxClient.InboxMessageSummary>();
        for (int i = page.length - 1; i >= 0; i--) {
            Message message = page[i];
            String[] ids = message.getHeader("Message-ID");
            Address[] senders = message.getFrom();
            Date received = message.getReceivedDate();
            result.add(new MailInboxClient.InboxMessageSummary(ids == null || ids.length == 0 ? "" : ids[0],
                    senders == null || senders.length == 0 ? "" : senders[0].toString(), message.getSubject(),
                    received == null ? Instant.EPOCH : received.toInstant()));
        }
        return new MailInboxClient.InboxPage(List.copyOf(result), start == 0 ? 0 : unread.length - start);
    }
}
