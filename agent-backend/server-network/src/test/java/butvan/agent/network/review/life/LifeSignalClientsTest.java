package butvan.agent.network.review.life;

import com.sun.net.httpserver.HttpServer;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 生产网络适配器回归：只连接本机假服务，不连接真实邮箱或探针。 */
class LifeSignalClientsTest {
    @Test void probeRejectsRedirectDownAndOversizedBodies() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.equals("/large") ? "x".repeat(40 * 1024)
                    : path.equals("/down") ? "{\"status\":\"DOWN\"}" : "{\"status\":\"UP\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.equals("/redirect") ? 302 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var probe = LifeSignalClients.probe();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(probe.probe(base + "/up").up());
            assertFalse(probe.probe(base + "/down").up());
            assertFalse(probe.probe(base + "/redirect").up());
            assertFalse(probe.probe(base + "/large").up());
        } finally { server.stop(0); }
    }

    @Test void mailboxWithoutTlsCannotReceiveLoginAndFailureIsNotAnEmptyInbox() throws Exception {
        var login = new AtomicBoolean();
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var worker = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(3000);
            var accepted = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
                    output.print("* OK local test\r\n"); output.flush();
                    String line;
                    while ((line = input.readLine()) != null) {
                        String tag = line.split(" ", 2)[0];
                        if (line.contains(" LOGIN ") || line.contains(" AUTHENTICATE ")) login.set(true);
                        if (line.contains("CAPABILITY")) output.print("* CAPABILITY IMAP4rev1\r\n" + tag + " OK done\r\n");
                        else { output.print("* BYE no TLS\r\n" + tag + " NO refused\r\n"); output.flush(); break; }
                        output.flush();
                    }
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            assertThrows(IllegalStateException.class, () -> LifeSignalClients.mail()
                    .fetchUnreadMessages("127.0.0.1", listener.getLocalPort(), "synthetic", "synthetic", 0));
            accepted.get(4, TimeUnit.SECONDS);
            assertFalse(login.get());
        }
    }

    @Test void unreadPaginationReachesTheTwentyFirstMessageWithoutReadingBodies() throws Exception {
        Folder inbox = mock(Folder.class);
        Message[] unread = new Message[21];
        for (int i = 0; i < unread.length; i++) {
            Message message = unread[i] = mock(Message.class);
            when(message.getHeader("Message-ID")).thenReturn(new String[]{"<synthetic-" + i + ">"});
            when(message.getFrom()).thenReturn(new Address[]{new InternetAddress("test@example.com")});
            when(message.getSubject()).thenReturn("虚构主题" + i);
        }
        when(inbox.search(any())).thenReturn(unread);
        var first = LifeSignalClients.readPage(inbox, 0);
        var second = LifeSignalClients.readPage(inbox, first.nextOffset());
        assertEquals(20, first.messages().size());
        assertEquals("<synthetic-0>", second.messages().getFirst().messageId());
        assertEquals(1, second.messages().size()); assertEquals(0, second.nextOffset());
        for (var message : unread) {
            verify(message, never()).getContent();
            verify(message, never()).setFlag(any(), anyBoolean());
        }
    }
}
