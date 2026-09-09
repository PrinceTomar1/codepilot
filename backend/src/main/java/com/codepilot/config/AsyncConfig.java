package com.codepilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /** Backs @Async indexing/webhook-review jobs so HTTP request threads never block on them. */
    @Bean(name = "indexingExecutor")
    public Executor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("codepilot-async-");
        executor.initialize();
        return executor;
    }

    /**
     * Backs the chatbot's SSE relay (QaService.askStream): one thread per in-flight streamed
     * answer, held for the whole generation while it pumps ai-service's SSE frames into the
     * client's SseEmitter. Kept separate from indexingExecutor so a burst of chat traffic can't
     * starve indexing/review jobs (and vice versa).
     */
    @Bean(name = "qaStreamExecutor")
    public Executor qaStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("codepilot-qa-stream-");
        executor.initialize();
        return executor;
    }
}
