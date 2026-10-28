package io.github.cskwork.lunepick.shared.internal.config;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class PlatformConfigurationTest {
    @Test
    void asyncExecutorBoundsWorkAndRejectsSaturationForDurableRetry() throws Exception {
        var executor = new PlatformConfiguration().applicationTaskExecutor(2, 2, 10);
        executor.initialize();
        var release = new CountDownLatch(1);
        Runnable wait =
                () -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        try {
            for (int i = 0; i < 4; i++) executor.execute(wait);
            assertThatThrownBy(() -> executor.execute(wait))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(executor.getPoolSize()).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void asyncConcurrencyMustLeaveConnectionsForRequestsAndSchedulers() {
        assertThatThrownBy(() -> new PlatformConfiguration().applicationTaskExecutor(5, 64, 10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void schedulerUsesBoundedPlatformThreadsEvenWhenVirtualThreadsAreEnabled() {
        var scheduler = new PlatformConfiguration().taskScheduler(3, 20);
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
        } finally {
            scheduler.shutdown();
        }
    }
}
