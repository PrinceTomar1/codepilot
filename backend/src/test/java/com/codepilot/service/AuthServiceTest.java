package com.codepilot.service;

import com.codepilot.dto.auth.AuthResponse;
import com.codepilot.dto.auth.RegisterRequest;
import com.codepilot.dto.auth.RegisterResponse;
import com.codepilot.dto.auth.MessageResponse;
import com.codepilot.entity.User;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.UserRepository;
import com.codepilot.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two things covered here:
 *  1. EmailService.sendVerificationEmail() used to silently swallow send failures, so the API
 *     always claimed "check your email" even when the send genuinely failed. register() now
 *     reflects real send status in its message; resendVerification() deliberately keeps its
 *     message constant regardless of send outcome (or whether the account even exists) to avoid
 *     leaking account existence to a prober.
 *  2. verifyCode() -- the code-entry alternative to clicking the emailed link -- returns the same
 *     generic "invalid or expired" message for a wrong code, an unknown email, and an expired
 *     code alike, so brute-forcing the ~1M possible 6-digit codes gets no signal about which
 *     guesses are "closer."
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private EmailService emailService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);

        authService = new AuthService(userRepository, passwordEncoder, jwtService, authenticationManager, emailService);
        ReflectionTestUtils.setField(authService, "verificationTokenExpirationMs", 86_400_000L);
        ReflectionTestUtils.setField(authService, "loginCodeExpirationMs", 600_000L);
        when(jwtService.generateToken(any(), anyString())).thenReturn("jwt-token");

        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerReportsSuccessWhenEmailSends() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(emailService.sendVerificationEmail(eq("alice@example.com"), anyString(), anyString())).thenReturn(true);

        RegisterResponse response = authService.register(new RegisterRequest("alice@example.com", "password123", "Alice"));

        assertThat(response.message()).isEqualTo(
                "Account created. Check your email to verify your address before signing in.");
    }

    @Test
    void registerReportsFailureWhenEmailDoesNotSend() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(emailService.sendVerificationEmail(eq("bob@example.com"), anyString(), anyString())).thenReturn(false);

        RegisterResponse response = authService.register(new RegisterRequest("bob@example.com", "password123", "Bob"));

        assertThat(response.message()).contains("couldn't send the verification email");
    }

    @Test
    void registerGeneratesASixDigitCode() {
        when(userRepository.existsByEmail("erin@example.com")).thenReturn(false);
        when(emailService.sendVerificationEmail(eq("erin@example.com"), anyString(), anyString())).thenReturn(true);

        authService.register(new RegisterRequest("erin@example.com", "password123", "Erin"));

        verify(emailService).sendVerificationEmail(eq("erin@example.com"), anyString(), org.mockito.ArgumentMatchers.matches("\\d{6}"));
    }

    @Test
    void resendVerificationMessageIsConstantWhenSendSucceeds() {
        User user = User.builder().id(UUID.randomUUID()).email("carol@example.com")
                .emailVerified(false).build();
        when(userRepository.findByEmail("carol@example.com")).thenReturn(Optional.of(user));
        when(emailService.sendVerificationEmail(eq("carol@example.com"), anyString(), anyString())).thenReturn(true);

        MessageResponse response = authService.resendVerification("carol@example.com");

        assertThat(response.message()).isEqualTo(
                "If that account exists and isn't verified yet, we've sent a new verification email.");
    }

    @Test
    void resendVerificationMessageIsIdenticalWhenSendFails() {
        // Same account, but the send itself fails -- the response text must not change, or a
        // prober could tell apart "account exists, send failed" from "account exists, send OK"
        // and "account doesn't exist" just by comparing wording.
        User user = User.builder().id(UUID.randomUUID()).email("dave@example.com")
                .emailVerified(false).build();
        when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
        when(emailService.sendVerificationEmail(eq("dave@example.com"), anyString(), anyString())).thenReturn(false);

        MessageResponse response = authService.resendVerification("dave@example.com");

        assertThat(response.message()).isEqualTo(
                "If that account exists and isn't verified yet, we've sent a new verification email.");
    }

    @Test
    void resendVerificationMessageIsIdenticalWhenAccountDoesNotExist() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        MessageResponse response = authService.resendVerification("nobody@example.com");

        assertThat(response.message()).isEqualTo(
                "If that account exists and isn't verified yet, we've sent a new verification email.");
    }

    @Test
    void registerRejectsDuplicateEmailBeforeAttemptingToSend() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("taken@example.com", "password123", "Taken")))
                .hasMessageContaining("already exists");

        verify(emailService, never()).sendVerificationEmail(any(), any(), any());
    }

    @Test
    void verifyCodeSucceedsWithMatchingUnexpiredCode() {
        User user = User.builder().id(UUID.randomUUID()).email("frank@example.com")
                .emailVerified(false).verificationCode("482913")
                .verificationTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("frank@example.com")).thenReturn(Optional.of(user));

        MessageResponse response = authService.verifyCode("frank@example.com", "482913");

        assertThat(response.message()).isEqualTo("Email verified. You can sign in now.");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerificationCode()).isNull();
    }

    @Test
    void verifyCodeRejectsWrongCodeWithGenericMessage() {
        User user = User.builder().id(UUID.randomUUID()).email("grace@example.com")
                .emailVerified(false).verificationCode("482913")
                .verificationTokenExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("grace@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyCode("grace@example.com", "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
    }

    @Test
    void verifyCodeRejectsUnknownEmailWithSameGenericMessage() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyCode("ghost@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
    }

    @Test
    void verifyCodeRejectsExpiredCodeWithDistinctMessage() {
        User user = User.builder().id(UUID.randomUUID()).email("henry@example.com")
                .emailVerified(false).verificationCode("482913")
                .verificationTokenExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("henry@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyCode("henry@example.com", "482913"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void requestLoginCodeSendsCodeAndGenericMessageForVerifiedAccount() {
        User user = User.builder().id(UUID.randomUUID()).email("liam@example.com")
                .emailVerified(true).build();
        when(userRepository.findByEmail("liam@example.com")).thenReturn(Optional.of(user));

        MessageResponse response = authService.requestLoginCode("liam@example.com");

        assertThat(response.message()).isEqualTo(
                "If that account exists and is verified, we've sent a sign-in code.");
        assertThat(user.getLoginCode()).matches("\\d{6}");
        assertThat(user.getLoginCodeExpiresAt()).isAfter(Instant.now());
        verify(emailService).sendLoginCodeEmail(eq("liam@example.com"), anyString());
    }

    @Test
    void requestLoginCodeSendsNothingForUnverifiedAccountButKeepsTheSameMessage() {
        // Passwordless login is an alternative to password login, not a way around the signup
        // email-verification step -- an unverified account must not be able to sign in via
        // either path. The response text still can't change, or it would leak "this account
        // exists but isn't verified" to a prober.
        User user = User.builder().id(UUID.randomUUID()).email("maya@example.com")
                .emailVerified(false).build();
        when(userRepository.findByEmail("maya@example.com")).thenReturn(Optional.of(user));

        MessageResponse response = authService.requestLoginCode("maya@example.com");

        assertThat(response.message()).isEqualTo(
                "If that account exists and is verified, we've sent a sign-in code.");
        assertThat(user.getLoginCode()).isNull();
        verify(emailService, never()).sendLoginCodeEmail(any(), any());
    }

    @Test
    void requestLoginCodeReturnsSameGenericMessageAndSendsNothingForUnknownEmail() {
        when(userRepository.findByEmail("ghost3@example.com")).thenReturn(Optional.empty());

        MessageResponse response = authService.requestLoginCode("ghost3@example.com");

        assertThat(response.message()).isEqualTo(
                "If that account exists and is verified, we've sent a sign-in code.");
        verify(emailService, never()).sendLoginCodeEmail(any(), any());
    }

    @Test
    void verifyLoginCodeSucceedsWithMatchingUnexpiredCodeAndReturnsARealSession() {
        User user = User.builder().id(UUID.randomUUID()).email("noah@example.com")
                .emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("noah@example.com")).thenReturn(Optional.of(user));

        AuthResponse response = authService.verifyLoginCode("noah@example.com", "482913");

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.user().email()).isEqualTo("noah@example.com");
        // Single-use: the code must not still work a second time.
        assertThat(user.getLoginCode()).isNull();
        assertThat(user.getLoginCodeExpiresAt()).isNull();
    }

    @Test
    void verifyLoginCodeRejectsWrongCodeWithGenericMessage() {
        User user = User.builder().id(UUID.randomUUID()).email("olivia@example.com")
                .emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("olivia@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyLoginCode("olivia@example.com", "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
    }

    @Test
    void verifyLoginCodeRejectsUnknownEmailWithSameGenericMessage() {
        when(userRepository.findByEmail("ghost4@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyLoginCode("ghost4@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
    }

    @Test
    void verifyLoginCodeRejectsExpiredCodeWithDistinctMessage() {
        User user = User.builder().id(UUID.randomUUID()).email("peter@example.com")
                .emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("peter@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyLoginCode("peter@example.com", "482913"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void resetPasswordWithCodeSucceedsWithMatchingUnexpiredCodeAndSignsTheUserIn() {
        User user = User.builder().id(UUID.randomUUID()).email("quinn@example.com")
                .passwordHash("old-hash").emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("quinn@example.com")).thenReturn(Optional.of(user));

        AuthResponse response = authService.resetPasswordWithCode("quinn@example.com", "482913", "newpassword123");

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.user().email()).isEqualTo("quinn@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("hashed");
        // Single-use: the same code must not still work a second time, for either purpose.
        assertThat(user.getLoginCode()).isNull();
        assertThat(user.getLoginCodeExpiresAt()).isNull();
    }

    @Test
    void resetPasswordWithCodeRejectsWrongCodeWithGenericMessageAndLeavesThePasswordAlone() {
        User user = User.builder().id(UUID.randomUUID()).email("riley@example.com")
                .passwordHash("old-hash").emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("riley@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resetPasswordWithCode("riley@example.com", "000000", "newpassword123"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void resetPasswordWithCodeRejectsUnknownEmailWithSameGenericMessage() {
        when(userRepository.findByEmail("ghost5@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPasswordWithCode("ghost5@example.com", "123456", "newpassword123"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
    }

    @Test
    void resetPasswordWithCodeRejectsExpiredCodeWithDistinctMessageAndLeavesThePasswordAlone() {
        User user = User.builder().id(UUID.randomUUID()).email("sam@example.com")
                .passwordHash("old-hash").emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("sam@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resetPasswordWithCode("sam@example.com", "482913", "newpassword123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void aCodeUsedToResetThePasswordCanNoLongerBeUsedToSignIn() {
        // The same login_code column backs both actions -- reset-then-reuse-for-login must fail
        // just as cleanly as reset-then-reuse-for-reset would, since resetPasswordWithCode()
        // clears it exactly like verifyLoginCode() does.
        User user = User.builder().id(UUID.randomUUID()).email("taylor@example.com")
                .passwordHash("old-hash").emailVerified(true).loginCode("482913")
                .loginCodeExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        when(userRepository.findByEmail("taylor@example.com")).thenReturn(Optional.of(user));

        authService.resetPasswordWithCode("taylor@example.com", "482913", "newpassword123");

        assertThatThrownBy(() -> authService.verifyLoginCode("taylor@example.com", "482913"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid or expired code.");
    }
}
