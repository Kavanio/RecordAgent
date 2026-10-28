package butvan.agent.network.memory.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.function.BooleanSupplier;

/** 只读探测仅允许固定 GET；共享有界传输不扩大探测用途。 */
final class HindsightProbeHttpClient implements AutoCloseable {
    private final HindsightHttpTransport http;
    HindsightProbeHttpClient(ObjectMapper mapper) { http = new HindsightHttpTransport(mapper); }
    JsonNode get(URI uri, String token, long deadline, BooleanSupplier cancelled) throws IOException, InterruptedException {
        var reply = http.request("GET", uri, token, null, deadline, cancelled);
        if (reply.status() != 200) throw new IOException("记忆连接探测响应无效");
        return reply.body();
    }
    @Override public void close() { http.close(); }
}
