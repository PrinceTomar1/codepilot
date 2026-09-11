package com.codepilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Periodically GETs a configured list of URLs so a PaaS host's edge routing to a rarely-visited
 * public domain doesn't go cold between real visitors -- observed live on Railway's Hobby tier:
 * both this app's own public domain and the frontend's took 4-6s to answer the first request
 * after a couple of days idle (edge/routing warm-up, not app restart -- the process itself had
 * been running the whole time), then under 0.5s on every request after. A background task
 * touching those same public URLs every few minutes keeps the route warm so a real visitor never
 * hits that cold path.
 *
 * Configured via {@code app.keep-warm.urls} ({@code KEEP_WARM_URLS} env var), a comma-separated
 * list of full URLs. Empty (the default) means this does nothing -- safe for local dev and any
 * deployment that isn't seeing this, no code change needed to opt out.
 */
@Component
public class KeepWarmPinger {

    private static final Logger log = LoggerFactory.getLogger(KeepWarmPinger.class);

    private final WebClient webClient;
    private final List<String> urls;

    public KeepWarmPinger(@Qualifier("keepWarmWebClient") WebClient webClient,
                           @Value("${app.keep-warm.urls:}") String urlsCsv) {
        this.webClient = webClient;
        this.urls = Arrays.stream(urlsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (!urls.isEmpty()) {
            log.info("Keep-warm pinger enabled for {} URL(s)", urls.size());
        }
    }

    /** Fires every 5 minutes -- comfortably inside whatever window a host's edge considers a
     * route "cold" again, without generating meaningful load. Fire-and-forget: a failed ping is
     * logged, never thrown, since this must never affect the app's own health. */
    @Scheduled(fixedRate = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void ping() {
        for (String url : urls) {
            webClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(10))
                    .doOnError(e -> log.debug("Keep-warm ping to {} failed: {}", url, e.toString()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
        }
    }
}
