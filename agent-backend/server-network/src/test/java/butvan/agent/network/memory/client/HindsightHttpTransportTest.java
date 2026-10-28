package butvan.agent.network.memory.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 上游只接受 HTTP/1.1 时，写入仍须保留完整 JSON，不用换掉有界取消传输实现。 */
class HindsightHttpTransportTest {
    @Test void postAndPutKeepJsonBodiesWithoutCleartextHttp2Upgrade() throws Exception {
        var mapper = new ObjectMapper(); var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            byte[] reply = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            try {
                // 模拟不支持 h2c 的部署：升级尝试下不承认请求体。
                if (exchange.getRequestHeaders().getFirst("Upgrade") != null) {
                    exchange.sendResponseHeaders(422, -1); return;
                }
                var body = mapper.readTree(exchange.getRequestBody());
                if (!"固定虚构内容".equals(body.path("content").asText())) { exchange.sendResponseHeaders(400, -1); return; }
                received.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply);
            } finally { exchange.close(); }
        });
        server.start();
        try (var transport = new HindsightHttpTransport(mapper)) {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            for (String method : new String[]{"POST", "PUT"}) {
                var result = transport.request(method, uri, "", mapper.createObjectNode().put("content", "固定虚构内容"),
                        System.nanoTime() + Duration.ofSeconds(2).toNanos(), () -> false);
                assertEquals(200, result.status(), method + " 不能触发明文 HTTP/2 升级而丢失有效正文");
                assertTrue(result.body().path("ok").asBoolean());
            }
            assertEquals(2, received.get());
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
