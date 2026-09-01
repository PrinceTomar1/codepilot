package com.codepilot.controller;

import com.codepilot.dto.auth.AuthResponse;
import com.codepilot.dto.auth.LoginCodeRequest;
import com.codepilot.dto.auth.LoginRequest;
import com.codepilot.dto.auth.MessageResponse;
import com.codepilot.dto.auth.RegisterRequest;
import com.codepilot.dto.auth.RegisterResponse;
import com.codepilot.dto.auth.ResendVerificationRequest;
import com.codepilot.dto.auth.UserDto;
import com.codepilot.dto.auth.VerifyCodeRequest;
import com.codepilot.exception.ApiException;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.AuthService;
import com.codepilot.service.GitHubOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String OAUTH_STATE_COOKIE = "gh_oauth_state";

    private final AuthService authService;
    private final GitHubOAuthService gitHubOAuthService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/verify")
    public ResponseEntity<MessageResponse> verify(@RequestParam String token) {
        return ResponseEntity.ok(authService.verifyEmail(token));
    }

    @PostMapping("/verify-code")
    public ResponseEntity<MessageResponse> verifyCode(@Valid @RequestBody VerifyCodeRequest request) {
        return ResponseEntity.ok(authService.verifyCode(request.email(), request.code()));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        return ResponseEntity.ok(authService.resendVerification(request.email()));
    }

    @PostMapping("/login-otp/request")
    public ResponseEntity<MessageResponse> requestLoginCode(@Valid @RequestBody LoginCodeRequest request) {
        return ResponseEntity.ok(authService.requestLoginCode(request.email()));
    }

    // Reuses VerifyCodeRequest (email + 6-digit code) -- identical shape and validation to the
    // email-verification code, just a different endpoint/meaning for what the code grants.
    @PostMapping("/login-otp/verify")
    public ResponseEntity<AuthResponse> verifyLoginCode(@Valid @RequestBody VerifyCodeRequest request) {
        return ResponseEntity.ok(authService.verifyLoginCode(request.email(), request.code()));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(authService.me(principal.getId()));
    }

    @GetMapping("/github/login")
    public void githubLogin(HttpServletResponse response) throws IOException {
        if (!gitHubOAuthService.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub sign-in is not configured (GITHUB_CLIENT_ID/GITHUB_CLIENT_SECRET missing).");
        }
        String state = gitHubOAuthService.generateState();
        ResponseCookie cookie = ResponseCookie.from(OAUTH_STATE_COOKIE, state)
                .httpOnly(true)
                .secure(isSecureContext())
                .path("/api/auth/github")
                .maxAge(Duration.ofMinutes(10))
                // Lax (not Strict): this cookie must still be sent on the top-level GET redirect
                // back from github.com after the user approves access.
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.sendRedirect(gitHubOAuthService.buildAuthorizeUrl(state));
    }

    @GetMapping("/github/callback")
    public void githubCallback(@RequestParam(required = false) String code,
                                @RequestParam(required = false) String state,
                                @RequestParam(required = false) String error,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        // Always clear the state cookie -- it's single-use regardless of outcome.
        ResponseCookie clear = ResponseCookie.from(OAUTH_STATE_COOKIE, "")
                .httpOnly(true).secure(isSecureContext()).path("/api/auth/github").maxAge(0).sameSite("Lax").build();
        response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());

        if (error != null) {
            redirectWithError(response, "GitHub sign-in was cancelled or denied.");
            return;
        }
        String expectedState = readCookie(request, OAUTH_STATE_COOKIE);
        if (code == null || state == null || expectedState == null || !expectedState.equals(state)) {
            redirectWithError(response, "GitHub sign-in failed (invalid or expired request). Please try again.");
            return;
        }

        String jwt;
        try {
            jwt = gitHubOAuthService.handleCallback(code);
        } catch (ApiException e) {
            redirectWithError(response, e.getMessage());
            return;
        }
        response.sendRedirect(frontendUrl + "/oauth-callback#token=" + jwt);
    }

    private void redirectWithError(HttpServletResponse response, String message) throws IOException {
        response.sendRedirect(frontendUrl + "/login?oauth_error="
                + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    // The Secure flag can't just be hardcoded true: local dev runs the backend over plain HTTP
    // (localhost:8080), and browsers silently refuse to send/store a Secure cookie there at all --
    // that would break GitHub OAuth locally. frontendUrl is already the one property that reliably
    // differs by scheme between the two (https:// in every real deployment, http://localhost in dev).
    private boolean isSecureContext() {
        return frontendUrl != null && frontendUrl.startsWith("https://");
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
