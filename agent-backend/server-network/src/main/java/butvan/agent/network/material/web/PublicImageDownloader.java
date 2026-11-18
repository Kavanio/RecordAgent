package butvan.agent.network.material.web;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.agent.AgentImages;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 从公开 HTTPS 站点取得有界的位图，实际连接只使用通过校验的 DNS 结果。 */
@Component
final class PublicImageDownloader {
    private static final Timeout SOCKET_TIMEOUT = Timeout.ofSeconds(3);
    private final CloseableHttpClient client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(new PublicDns())
                    .setMaxConnTotal(4)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(SOCKET_TIMEOUT)
                            .setSocketTimeout(SOCKET_TIMEOUT)
                            .build())
                    .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(SOCKET_TIMEOUT)
                    .setResponseTimeout(SOCKET_TIMEOUT)
                    .build())
            .disableCookieManagement()
            .disableAuthCaching()
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableContentCompression()
            .build();

    /** 首版只处理直接返回图片的公开 HTTPS URL。 */
    DownloadedImage download(String url) throws IOException, InterruptedException {
        URI uri = validateUrl(url);
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", "image/png,image/jpeg,image/webp,image/gif");
        var task = new FutureTask<>(() -> client.execute(request, response -> {
            var entity = response.getEntity();
            if (response.getCode() != 200 || entity == null) throw new IOException("image unavailable");
            String contentType = entity.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IOException("not an image response");
            }
            if (entity.getContentLength() > AgentImageAttachment.MAX_BYTES) throw new IOException("image too large");
            try (var input = entity.getContent()) {
                byte[] bytes = input.readNBytes(AgentImageAttachment.MAX_BYTES + 1);
                if (bytes.length > AgentImageAttachment.MAX_BYTES) request.cancel();
                return new DownloadedImage(AgentImages.parse(bytes), uri.toASCIIString());
            }
        }));
        Thread.ofVirtual().start(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("image download failed", exception);
        } finally {
            if (!task.isDone()) {
                request.cancel();
                task.cancel(true);
            }
        }
    }

    static URI validateUrl(String address) {
        if (address == null || address.length() > 2048) throw new IllegalArgumentException("无效的图片地址");
        URI uri = URI.create(address);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !host.contains(".") || !host.matches("(?i)[a-z0-9.-]*[a-z][a-z0-9.-]*")) {
            throw new IllegalArgumentException("图片地址须为公开 HTTPS 域名");
        }
        return uri;
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        if (address instanceof Inet6Address) {
            // 只允许全球单播地址，排除可能封装内网 IPv4 的转换和隧道地址。
            return (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x02)
                    && !(first == 0x20 && second == 0x01 && (third < 2 || (third == 0x0d && (bytes[3] & 0xff) == 0xb8)))
                    && !(first == 0x3f && second == 0xff);
        }
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 192 && second == 0 && (third == 0 || third == 2))
                && !(first == 192 && second == 88 && third == 99)
                && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                && !(first == 203 && second == 0 && third == 113);
    }

    @PreDestroy
    void close() throws IOException {
        client.close();
    }

    record DownloadedImage(AgentImageAttachment image, String verifiedUrl) { }

    private static final class PublicDns implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (!publicAddress(address)) throw new UnknownHostException("non-public image host");
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
