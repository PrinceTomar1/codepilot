package com.codepilot.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KeepWarmPinger touches a configured list of public URLs on a timer so a PaaS host's edge
 * routing doesn't go cold between real visitors (see the class Javadoc for the incident this
 * came from). Exercised against a real local HTTP server -- like AiServiceClientTest, a mocked
 * WebClient wouldn't prove an actual GET is issued.
 */
class KeepWarmPingerTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private WebClient webClient() {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector()).build();
    }

    @Test
    void pingsEveryConfiguredUrl() throws Exception {
        AtomicInteger hitsA = new AtomicInteger(0);
        AtomicInteger hitsB = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/a", exchange -> {
            hitsA.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
        });
        server.createContext("/b", exchange -> {
            hitsB.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();
        int port = server.getAddress().getPort();

        KeepWarmPinger pinger = new KeepWarmPinger(
                webClient(), "http://localhost:" + port + "/a, http://localhost:" + port + "/b");

        pinger.ping();

        // ping() fires the requests non-blockingly and returns immediately, so give them a
        // moment to land rather than asserting instantly.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (hitsA.get() == 0 || hitsB.get() == 0)) {
            Thread.sleep(50);
        }

        assertThat(hitsA.get()).isEqualTo(1);
        assertThat(hitsB.get()).isEqualTo(1);
    }

    @Test
    void blankConfigPingsNothing() throws Exception {
        AtomicInteger hits = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        KeepWarmPinger pinger = new KeepWarmPinger(webClient(), "");
        pinger.ping();

        // Nothing to await on a true no-op -- a short grace period confirms no request was ever
        // fired rather than one that just hasn't arrived yet.
        Thread.sleep(200);
        assertThat(hits.get()).isZero();
    }

    @Test
    void aFailedPingDoesNotThrow() {
        // Nothing listening on this port -- KeepWarmPinger must swallow the connection failure,
        // not propagate it (a failed background ping must never affect the app's own health).
        KeepWarmPinger pinger = new KeepWarmPinger(webClient(), "http://localhost:1/unreachable");

        pinger.ping();
    }
}
