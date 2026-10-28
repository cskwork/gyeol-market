package io.github.cskwork.lunepick.member.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.shared.ApiException;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class BoundedPasswordEncoderTest {
    @Test
    void concurrentPasswordWorkIsBoundedAndPermitsReturnAfterSuccess() throws Exception {
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(delegate.encode("first"))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await();
                            return "hash";
                        });
        when(delegate.encode("later")).thenReturn("next");
        var bounded = new BoundedPasswordEncoder(delegate, 1, Duration.ZERO);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> bounded.encode("first"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> bounded.encode("busy"))
                        .isInstanceOfSatisfying(
                                ApiException.class,
                                e -> {
                                    assertThat(e.status()).isEqualTo(503);
                                    assertThat(e.code()).isEqualTo("AUTH_CAPACITY_EXCEEDED");
                                });
            } finally {
                release.countDown();
            }
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("hash");
        }
        assertThat(bounded.encode("later")).isEqualTo("next");
        verify(delegate, never()).encode("busy");
    }

    @Test
    void failureAndInterruptionNeverLeakPermits() {
        var delegate = mock(PasswordEncoder.class);
        when(delegate.matches("bad", "hash"))
                .thenThrow(new IllegalArgumentException("synthetic failure"));
        when(delegate.matches("ok", "hash")).thenReturn(true);
        when(delegate.upgradeEncoding("hash")).thenReturn(true);
        var bounded = new BoundedPasswordEncoder(delegate, 1, Duration.ZERO);
        assertThatThrownBy(() -> bounded.matches("bad", "hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bounded.matches("ok", "hash")).isTrue();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> bounded.matches("ok", "hash"))
                    .isInstanceOf(ApiException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(bounded.matches("ok", "hash")).isTrue();
        assertThat(bounded.upgradeEncoding("hash")).isTrue();
    }
}
