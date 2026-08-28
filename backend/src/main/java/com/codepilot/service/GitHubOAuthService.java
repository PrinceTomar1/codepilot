package com.codepilot.service;

import com.codepilot.dto.github.GitHubEmail;
import com.codepilot.dto.github.GitHubOAuthTokenResponse;
import com.codepilot.dto.github.GitHubUser;
import com.codepilot.entity.User;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.UserRepository;
import com.codepilot.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * GitHub OAuth login/signup. Distinct from the manual-PAT repository-connection flow
 * (RepositoryService/EncryptionService still handle that) -- this is specifically "sign in with
 * GitHub," which additionally stores the resulting access token so repositories can later be
 * listed/connected without the user pasting a token by hand (see RepositoryService.fromGitHub).
 */
@Service
@RequiredArgsConstructor
public class GitHubOAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";

    private final UserRepository userRepository;
    private final GitHubClient gitHubClient;
    private final EncryptionService encryptionService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Qualifier("gitHubOAuthWebClient")
    private final WebClient gitHubOAuthWebClient;

    @Value("${app.github.oauth.client-id}")
    private String clientId;

    @Value("${app.github.oauth.client-secret}")
    private String clientSecret;

    @Value("${app.github.oauth.redirect-uri}")
    private String redirectUri;

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public String generateState() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromHttpUrl(AUTHORIZE_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "repo user:email")
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    /** Exchanges the OAuth `code` for an access token, upserts the User, and returns a JWT. */
    @Transactional
    public String handleCallback(String code) {
        String accessToken = exchangeCodeForToken(code);
        GitHubUser githubUser = gitHubClient.fetchAuthenticatedUser(accessToken);
        if (githubUser == null || githubUser.id() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub did not return a valid user profile");
        }
        String email = resolveVerifiedEmail(githubUser, accessToken);

        User user = userRepository.findByGithubId(githubUser.id())
                .or(() -> userRepository.findByEmail(email))
                .orElseGet(() -> User.builder()
                        // OAuth-only accounts never use this password -- a long random value
                        // that can't be guessed, hashed the same as a real password would be.
                        .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                        .build());

        user.setEmail(email);
        user.setName(user.getName() == null || user.getName().isBlank() ? githubUser.name() : user.getName());
        // GitHub already verified control of this email address (it's their account's verified
        // email) -- no need to make them click a second verification link on top of that.
        user.setEmailVerified(true);
        user.setGithubId(githubUser.id());
        user.setGithubUsername(githubUser.login());
        user.setGithubAccessTokenEncrypted(encryptionService.encrypt(accessToken));
        user = userRepository.save(user);

        return jwtService.generateToken(user.getId(), user.getEmail());
    }

    private String exchangeCodeForToken(String code) {
        GitHubOAuthTokenResponse response;
        try {
            response = gitHubOAuthWebClient.post()
                    .uri("/login/oauth/access_token")
                    .body(BodyInserters.fromFormData("client_id", clientId)
                            .with("client_secret", clientSecret)
                            .with("code", code)
                            .with("redirect_uri", redirectUri))
                    .retrieve()
                    .bodyToMono(GitHubOAuthTokenResponse.class)
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to exchange GitHub OAuth code: " + e.getMessage(), e);
        }

        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            String detail = response != null && response.errorDescription() != null ? response.errorDescription() : "no token returned";
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub OAuth token exchange failed: " + detail);
        }
        return response.accessToken();
    }

    private String resolveVerifiedEmail(GitHubUser githubUser, String accessToken) {
        if (githubUser.email() != null && !githubUser.email().isBlank()) {
            return githubUser.email().toLowerCase();
        }
        List<GitHubEmail> emails = gitHubClient.fetchUserEmails(accessToken);
        Optional<GitHubEmail> primary = emails.stream()
                .filter(e -> e.primary() && e.verified())
                .findFirst();
        if (primary.isPresent()) {
            return primary.get().email().toLowerCase();
        }
        Optional<GitHubEmail> anyVerified = emails.stream().filter(GitHubEmail::verified).findFirst();
        if (anyVerified.isPresent()) {
            return anyVerified.get().email().toLowerCase();
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "Your GitHub account has no verified email address to sign in with.");
    }
}
