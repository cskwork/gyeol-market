package io.github.cskwork.lunepick.member.internal.application;

import io.github.cskwork.lunepick.shared.ApiException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Bounds CPU-intensive password work independently of database connection capacity. */
public class BoundedPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final Duration wait;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int concurrency, Duration wait) {
        this.delegate = delegate;
        this.permits = new Semaphore(concurrency);
        this.wait = wait;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return execute(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return execute(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private <T> T execute(Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) throw capacity();
            return action.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw capacity();
        } finally {
            if (acquired) permits.release();
        }
    }

    private ApiException capacity() {
        return new ApiException(503, "AUTH_CAPACITY_EXCEEDED", "인증 요청이 많습니다. 잠시 후 다시 시도해주세요.");
    }
}
