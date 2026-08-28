package com.codepilot.service;

import com.codepilot.dto.github.GitHubEmail;
import com.codepilot.dto.github.GitHubUser;
import com.codepilot.entity.User;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.UserRepository;
import com.codepilot.security.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real WebClient token-exchange call against a local HTTP server (same pattern as
 * AiServiceClientTest -- a mocked WebClient can't reliably reproduce the real request/response
 * pipeline), with GitHubClient/UserRepository mocked since those are already covered by their own
 * tests elsewhere.
 */
class GitHubOAuthServiceTest {

    private HttpServer server;
    private UserRepository userRepository;
    private GitHubClient gitHubClient;
    private EncryptionService encryptionService;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private GitHubOAuthService service;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startTokenServer(String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/login/oauth/access_token", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
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

        userRepository = mock(UserRepository.class);
        gitHubClient = mock(GitHubClient.class);
        encryptionService = mock(EncryptionService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);

        service = new GitHubOAuthService(userRepository, gitHubClient, encryptionService, passwordEncoder, jwtService, webClient);
        ReflectionTestUtils.setField(service, "clientId", "test-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "test-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/api/auth/github/callback");

        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(encryptionService.encrypt(anyString())).thenReturn("encrypted-token");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(), anyString())).thenReturn("fake-jwt");
    }

    @Test
    void buildAuthorizeUrlIncludesRequiredParams() {
        userRepository = mock(UserRepository.class);
        gitHubClient = mock(GitHubClient.class);
        encryptionService = mock(EncryptionService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        WebClient webClient = WebClient.builder().baseUrl("https://github.com").build();
        service = new GitHubOAuthService(userRepository, gitHubClient, encryptionService, passwordEncoder, jwtService, webClient);
        ReflectionTestUtils.setField(service, "clientId", "abc123");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/api/auth/github/callback");

        String url = service.buildAuthorizeUrl("state-xyz");

        assertThat(url).startsWith("https://github.com/login/oauth/authorize")
                .contains("client_id=abc123")
                .contains("state=state-xyz")
                .contains("scope=repo")
                .contains("redirect_uri=");
    }

    @Test
    void newGithubUserCreatesAccountWithVerifiedEmail() throws Exception {
        startTokenServer("{\"access_token\":\"gho_realtoken\"}");
        when(gitHubClient.fetchAuthenticatedUser("gho_realtoken"))
                .thenReturn(new GitHubUser(999L, "octocat", "The Octocat", "octocat@example.com"));
        when(userRepository.findByGithubId(999L)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("octocat@example.com")).thenReturn(Optional.empty());

        String jwt = service.handleCallback("real-code");

        assertThat(jwt).isEqualTo("fake-jwt");
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("octocat@example.com");
        assertThat(saved.isEmailVerified()).isTrue();
        assertThat(saved.getGithubId()).isEqualTo(999L);
        assertThat(saved.getGithubUsername()).isEqualTo("octocat");
        assertThat(saved.getGithubAccessTokenEncrypted()).isEqualTo("encrypted-token");
    }

    @Test
    void existingUserMatchedByGithubIdIsUpdatedNotDuplicated() throws Exception {
        startTokenServer("{\"access_token\":\"gho_realtoken\"}");
        User existing = User.builder().id(UUID.randomUUID()).email("old@example.com")
                .githubId(999L).emailVerified(true).build();
        when(gitHubClient.fetchAuthenticatedUser("gho_realtoken"))
                .thenReturn(new GitHubUser(999L, "octocat", "The Octocat", "new@example.com"));
        when(userRepository.findByGithubId(999L)).thenReturn(Optional.of(existing));

        service.handleCallback("real-code");

        org.mockito.Mockito.verify(userRepository).save(existing);
        assertThat(existing.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void fallsBackToUserEmailsEndpointWhenProfileEmailIsPrivate() throws Exception {
        startTokenServer("{\"access_token\":\"gho_realtoken\"}");
        when(gitHubClient.fetchAuthenticatedUser("gho_realtoken"))
                .thenReturn(new GitHubUser(999L, "octocat", "The Octocat", null));
        when(gitHubClient.fetchUserEmails("gho_realtoken")).thenReturn(List.of(
                new GitHubEmail("secondary@example.com", false, true),
                new GitHubEmail("primary@example.com", true, true)
        ));
        when(userRepository.findByGithubId(999L)).thenReturn(Optional.empty());
        when(userRepository.findByEmail("primary@example.com")).thenReturn(Optional.empty());

        service.handleCallback("real-code");

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("primary@example.com");
    }

    @Test
    void tokenExchangeErrorSurfacesAsApiException() throws Exception {
        startTokenServer("{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}");

        assertThatThrownBy(() -> service.handleCallback("stale-code"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("incorrect or expired");
    }

    @Test
    void isConfiguredReflectsClientCredentials() {
        userRepository = mock(UserRepository.class);
        gitHubClient = mock(GitHubClient.class);
        encryptionService = mock(EncryptionService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        WebClient webClient = WebClient.builder().build();
        service = new GitHubOAuthService(userRepository, gitHubClient, encryptionService, passwordEncoder, jwtService, webClient);

        ReflectionTestUtils.setField(service, "clientId", "");
        ReflectionTestUtils.setField(service, "clientSecret", "");
        assertThat(service.isConfigured()).isFalse();

        ReflectionTestUtils.setField(service, "clientId", "id");
        ReflectionTestUtils.setField(service, "clientSecret", "secret");
        assertThat(service.isConfigured()).isTrue();
    }
}
