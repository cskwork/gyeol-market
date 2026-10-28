package io.github.cskwork.lunepick.shared.internal.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    ThreadPoolTaskExecutor applicationTaskExecutor(
            @Value("${lunepick.async.concurrency:4}") int concurrency,
            @Value("${lunepick.async.queue-capacity:64}") int queue,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int connections) {
        if (concurrency < 1 || concurrency * 2 >= connections || queue < 1)
            throw new IllegalStateException(
                    "Async concurrency must leave more than half the database pool for requests");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("module-events-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:3}") int threads,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int connections) {
        if (threads < 1 || threads * 2 >= connections)
            throw new IllegalStateException(
                    "Scheduler threads must leave more than half the database pool for requests");
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threads);
        scheduler.setThreadNamePrefix("maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
