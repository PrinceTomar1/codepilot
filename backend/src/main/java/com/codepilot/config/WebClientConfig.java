package com.codepilot.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    /** LLM calls on the Python side can be slow, so a generous 60s timeout is used. */
    private static final int TIMEOUT_MS = 60_000;

    // PR review runs 4 agents concurrently plus a summarizer -- real-world LLM latency variance
    // means one of those five calls regularly exceeds 60s even though the other four return in a
    // few seconds (confirmed live: 3-4 back within 3s, one taking 30s+). The 60s shared timeout
    // was cutting the whole review off mid-flight, and since ai-service has no way to resume a
    // request the caller already gave up on, every retry just restarted the same slow work from
    // scratch -- it could never actually finish. Review is already invoked via @Async
    // (PrReviewService), so nothing user-facing blocks on this longer budget.
    private static final int REVIEW_TIMEOUT_MS = 180_000;

    @Bean
    public WebClient aiServiceWebClient(@Value("${app.ai-service.base-url}") String baseUrl) {
        return buildAiServiceWebClient(baseUrl, TIMEOUT_MS);
    }

    @Bean
    public WebClient aiServiceReviewWebClient(@Value("${app.ai-service.base-url}") String baseUrl) {
        return buildAiServiceWebClient(baseUrl, REVIEW_TIMEOUT_MS);
    }

    private WebClient buildAiServiceWebClient(String baseUrl, int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.min(timeoutMs, TIMEOUT_MS))
                .responseTimeout(java.time.Duration.ofMillis(timeoutMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /** github.com (not api.github.com) -- used only for the OAuth code-for-token exchange. */
    @Bean
    public WebClient gitHubOAuthWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_MS)
                .responseTimeout(java.time.Duration.ofMillis(TIMEOUT_MS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(TIMEOUT_MS, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl("https://github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** api.sendgrid.com -- see EmailService; only used when app.mail.provider=sendgrid. */
    @Bean
    public WebClient sendgridWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_MS)
                .responseTimeout(java.time.Duration.ofMillis(TIMEOUT_MS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(TIMEOUT_MS, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl("https://api.sendgrid.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public WebClient gitHubWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_MS)
                .responseTimeout(java.time.Duration.ofMillis(TIMEOUT_MS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(TIMEOUT_MS, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
