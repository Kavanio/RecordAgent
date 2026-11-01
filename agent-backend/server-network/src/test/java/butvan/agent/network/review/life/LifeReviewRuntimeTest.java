package butvan.agent.network.review.life;

import butvan.agent.agents.reset.ResetBarrier;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 模型生成阻塞时，现实信号仍能继续被调度；使用闩锁而非定时等待。 */
class LifeReviewRuntimeTest {
    @Test void slowGenerationDoesNotBlockOutreach() throws Exception {
        var repository = mock(LifeReviewRepository.class);
        var reviews = mock(LifeReviewService.class);
        var outreach = mock(LifeOutreachService.class);
        var barrier = mock(ResetBarrier.class);
        when(repository.enabled()).thenReturn(List.of(LifeReviewModels.Settings.defaults("owner")));
        when(repository.latest(anyString(), any())).thenReturn(Optional.empty());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return null; }).when(reviews).tick();
        var runtime = new LifeReviewRuntime(repository, reviews, outreach, barrier,
                Clock.fixed(Instant.parse("2026-11-01T13:00:00Z"), ZoneOffset.UTC));
        try (var tasks = Executors.newFixedThreadPool(2)) {
            var generating = tasks.submit(runtime::generate);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                tasks.submit(runtime::tick).get(2, TimeUnit.SECONDS);
                verify(outreach).tick("owner");
            } finally { release.countDown(); }
            generating.get(2, TimeUnit.SECONDS);
        } finally { runtime.close(); }
    }
}
