package io.github.cskwork.lunepick.order.internal.config;

import io.github.cskwork.lunepick.order.internal.application.OrderService;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderExpiryConfiguration.ExpiryProperties.class)
public class OrderExpiryConfiguration {
    private final OrderService orders;
    private final ExpiryProperties properties;

    public OrderExpiryConfiguration(OrderService orders, ExpiryProperties properties) {
        this.orders = orders;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${lunepick.order.expiry.delay:PT1M}")
    public void expire() {
        orders.expirePending(properties.ttl(), properties.batchSize());
    }

    @Scheduled(fixedDelayString = "${lunepick.order.replay-cleanup-delay:PT1H}")
    public void cleanup() {
        orders.forgetExpiredReplayKeys(properties.replayRetention());
    }

    @ConfigurationProperties("lunepick.order.expiry")
    public record ExpiryProperties(Duration ttl, Integer batchSize, Duration replayRetention) {
        public ExpiryProperties {
            replayRetention = replayRetention == null ? Duration.ofHours(24) : replayRetention;
            ttl = ttl == null ? Duration.ofMinutes(30) : ttl;
            batchSize = batchSize == null ? 100 : batchSize;
            if (replayRetention.isNegative()
                    || replayRetention.isZero()
                    || ttl.isNegative()
                    || ttl.isZero()
                    || batchSize < 1
                    || batchSize > 1000)
                throw new IllegalArgumentException(
                        "Order expiry requires positive TTL and batch-size 1..1000");
        }
    }
}
