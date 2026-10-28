package butvan.agent.network.memory.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/** 有界只读 HTTP：固定 GET、禁止重定向、共享总期限并取消实际 exchange。 */
final class HindsightProbeHttpClient implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(1)).build();

    HindsightProbeHttpClient(ObjectMapper mapper) { this.mapper = mapper; }

    JsonNode get(URI uri, String token, long deadline, BooleanSupplier cancelled) throws IOException, InterruptedException {
        check(deadline, cancelled);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(deadline - System.nanoTime()))
                .header("Accept", "application/json").GET();
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        var future = client.sendAsync(request.build(), ignored -> new LimitedBody());
        try {
            HttpResponse<byte[]> response;
            while (true) {
                check(deadline, cancelled);
                try { response = future.get(Math.min(20, Math.max(1, remainingMillis(deadline))), TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException waiting) { /* 使用同一总期限，不给后续 GET 重置预算。 */ }
                catch (ExecutionException failure) { throw new IOException("记忆连接探测失败"); }
            }
            check(deadline, cancelled);
            if (response.statusCode() != 200) throw new IOException("记忆连接探测响应无效");
            String contentType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
            if (!contentType.equalsIgnoreCase("application/json")) throw new IOException("记忆连接探测不是 JSON");
            JsonNode value = mapper.readTree(response.body());
            check(deadline, cancelled);
            if (value == null || !value.isObject()) throw new IOException("记忆连接探测结构无效");
            return value;
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private void check(long deadline, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("记忆连接探测已取消");
        if (cancelled.getAsBoolean()) throw new IOException("记忆连接探测已取消");
        if (System.nanoTime() >= deadline) throw new IOException("记忆连接探测超时");
    }
    private long remainingMillis(long deadline) { return (deadline - System.nanoTime()) / 1_000_000; }
    @Override public void close() { client.shutdownNow(); }

    /** 在读取流时执行实际字节上限，不能先无限聚合后检查 Content-Length。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IOException("记忆探测响应超过上限")); return;
                }
                byte[] next = new byte[buffer.remaining()]; buffer.get(next); bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { body.completeExceptionally(new IOException("记忆探测读取失败")); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
