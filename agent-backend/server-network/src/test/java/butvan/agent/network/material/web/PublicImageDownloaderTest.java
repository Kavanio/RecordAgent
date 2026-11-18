package butvan.agent.network.material.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicImageDownloaderTest {
    @Test
    void onlyAcceptsPublicHttpsTargets() throws Exception {
        for (String url : List.of("http://example.net/p.png", "https://127.0.0.1/p.png",
                "https://[::1]/p.png", "https://user@example.net/p.png", "https://example.net:8080/p.png",
                "file:///tmp/p.png")) {
            assertThrows(IllegalArgumentException.class, () -> PublicImageDownloader.validateUrl(url));
        }
        assertEquals("example.net", PublicImageDownloader.validateUrl("https://example.net/p.png?width=200").getHost());
        for (String ip : List.of("127.0.0.1", "192.168.1.1", "100.64.0.1", "169.254.169.254", "::1",
                "::ffff:127.0.0.1", "fd00::1", "2002:c0a8:101::1")) {
            assertFalse(PublicImageDownloader.publicAddress(InetAddress.getByName(ip)), ip);
        }
        assertTrue(PublicImageDownloader.publicAddress(InetAddress.getByName("93.184.215.14")));
    }
}
