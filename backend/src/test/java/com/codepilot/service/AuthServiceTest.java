package com.codepilot.service;

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
}
