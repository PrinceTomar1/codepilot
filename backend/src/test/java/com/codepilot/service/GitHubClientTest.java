package com.codepilot.service;

import com.codepilot.dto.ai.AiFileContent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises fetchRepositoryFiles()'s file-selection logic against a local HTTP server standing in
 * for the GitHub API (same pattern as GitHubOAuthServiceTest). Covers the security-relevant path:
 * secret-bearing files (.env, .env.local, private keys, credentials.json) must never reach the
 * indexer, since indexed content is embedded and becomes retrievable via the RAG chatbot.
 */
class GitHubClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GitHubClient startServerWithFiles(Map<String, String> pathToContent) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);

        List<String> paths = List.copyOf(pathToContent.keySet());
        String treeJson = "{\"sha\":\"root\",\"truncated\":false,\"tree\":[" + paths.stream()
                .map(p -> "{\"path\":" + jsonString(p) + ",\"mode\":\"100644\",\"type\":\"blob\","
                        + "\"sha\":" + jsonString(shaFor(p)) + ",\"size\":" + pathToContent.get(p).length() + "}")
                .collect(Collectors.joining(",")) + "]}";

        server.createContext("/repos/octocat/hello-world/git/trees/main", exchange -> {
            byte[] bytes = treeJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/repos/octocat/hello-world/git/blobs/", exchange -> {
            String sha = exchange.getRequestURI().getPath().substring(
                    exchange.getRequestURI().getPath().lastIndexOf('/') + 1);
            String path = paths.stream().filter(p -> shaFor(p).equals(sha)).findFirst().orElseThrow();
            String base64 = Base64.getEncoder().encodeToString(pathToContent.get(path).getBytes(StandardCharsets.UTF_8));
            String blobJson = "{\"sha\":" + jsonString(sha) + ",\"content\":" + jsonString(base64)
                    + ",\"encoding\":\"base64\",\"size\":" + pathToContent.get(path).length() + "}";
            byte[] bytes = blobJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();

        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .clientConnector(new ReactorClientHttpConnector())
                .build();
        return new GitHubClient(webClient);
    }

    private static String shaFor(String path) {
        return "sha-" + path.replaceAll("[^a-zA-Z0-9]", "-");
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void secretBearingFilesAreExcludedFromIndexing() throws Exception {
        GitHubClient client = startServerWithFiles(Map.of(
                ".env", "API_KEY=super-secret-value",
                ".env.local", "DB_PASSWORD=another-secret",
                ".env.example", "API_KEY=your-key-here",
                "id_rsa", "-----BEGIN OPENSSH PRIVATE KEY-----",
                "certs/private.pem", "-----BEGIN PRIVATE KEY-----",
                "config/credentials.json", "{\"apiKey\":\"secret\"}",
                "src/main.py", "print('hello world')"
        ));

        List<AiFileContent> files = client.fetchRepositoryFiles("octocat", "hello-world", "main", "token");
        List<String> indexedPaths = files.stream().map(AiFileContent::path).toList();

        assertThat(indexedPaths).containsExactlyInAnyOrder("src/main.py", ".env.example");
        assertThat(indexedPaths).doesNotContain(
                ".env", ".env.local", "id_rsa", "certs/private.pem", "config/credentials.json");
    }

    @Test
    void dependencyLockfilesAreExcludedFromIndexing() throws Exception {
        // Real bug: a huge, machine-generated lockfile has no value for understanding a codebase,
        // but its package names can substring-match a real keyword by accident (e.g.
        // "micromark-util-symbol" matching a search for "symbol") -- enough such incidental
        // matches can outrank and bury genuinely relevant chunks in keyword search. Confirmed live
        // against a real repository: the query "gold symbol" never surfaced the two chunks that
        // actually mention "gold" because pnpm-lock.yaml alone contributed several higher-scoring
        // "symbol" matches ahead of them.
        GitHubClient client = startServerWithFiles(Map.of(
                "package-lock.json", "{\"packages\":{}}",
                "yarn.lock", "# yarn lockfile v1",
                "pnpm-lock.yaml", "lockfileVersion: '9.0'",
                "Gemfile.lock", "GEM\n  remote: https://rubygems.org/",
                "go.sum", "example.com/pkg v1.0.0 h1:abc=",
                "src/main.py", "print('hello world')"
        ));

        List<AiFileContent> files = client.fetchRepositoryFiles("octocat", "hello-world", "main", "token");
        List<String> indexedPaths = files.stream().map(AiFileContent::path).toList();

        assertThat(indexedPaths).containsExactly("src/main.py");
    }
}
