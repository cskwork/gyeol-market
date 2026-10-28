package io.github.cskwork.lunepick.shared.internal.application;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.github.cskwork.lunepick.shared.internal.config.RateLimitProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        name = "lunepick.rate-limit.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RateLimitService {
    private final ProxyManager<Long> manager;
    private final RateLimitProperties properties;
    private final JdbcTemplate jdbc;

    public RateLimitService(
            DataSource dataSource, RateLimitProperties properties, JdbcTemplate jdbc) {
        this.manager =
                Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                        .table("rate_limit_buckets")
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                        Duration.ofMinutes(5)))
                        .build();
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public long retryAfter(String scope, String subject, long capacity) {
        var configuration =
                BucketConfiguration.builder()
                        .addLimit(
                                limit ->
                                        limit.capacity(capacity)
                                                .refillIntervally(capacity, properties.window()))
                        .build();
        var probe =
                manager.builder()
                        .build(key(scope, subject), () -> configuration)
                        .tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? 0
                : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
    }

    static long key(String scope, String subject) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((scope + ":" + subject).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Scheduled(fixedDelayString = "${lunepick.rate-limit.cleanup-delay:PT5M}")
    public void cleanup() {
        jdbc.update(
                "DELETE FROM rate_limit_buckets WHERE expires_at < ?", System.currentTimeMillis());
    }
}
