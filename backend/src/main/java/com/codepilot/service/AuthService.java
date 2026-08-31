package com.codepilot.service;

import com.codepilot.dto.auth.AuthResponse;
import com.codepilot.dto.auth.LoginRequest;
import com.codepilot.dto.auth.MessageResponse;
import com.codepilot.dto.auth.RegisterRequest;
import com.codepilot.dto.auth.RegisterResponse;
import com.codepilot.dto.auth.UserDto;
import com.codepilot.entity.User;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.UserRepository;
import com.codepilot.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;

    @Value("${app.verification.token-expiration-ms}")
    private long verificationTokenExpirationMs;

    @Value("${app.password-reset.token-expiration-ms}")
    private long resetTokenExpirationMs;

    private static String generateVerificationCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
        }

        String token = UUID.randomUUID().toString();
        String code = generateVerificationCode();
        User user = User.builder()
                .email(request.email().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name())
                .emailVerified(false)
                .verificationToken(token)
                .verificationCode(code)
                .verificationTokenExpiresAt(Instant.now().plusMillis(verificationTokenExpirationMs))
                .build();
        user = userRepository.save(user);

        boolean emailSent = emailService.sendVerificationEmail(user.getEmail(), token, code);
        // Registering reveals its own existence anyway (see the 409 check above), so unlike
        // resendVerification() there's no anti-enumeration reason to hide a genuine send failure
        // from the person who just typed in their own email address.
        String message = emailSent
                ? "Account created. Check your email to verify your address before signing in."
                : "Account created, but we couldn't send the verification email right now. "
                        + "Use \"Resend verification email\" in a moment to try again.";

        return new RegisterResponse(toDto(user), message);
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email().toLowerCase(), request.password()));
        } catch (DisabledException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Please verify your email before signing in");
        } catch (BadCredentialsException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        User user = userRepository.findByEmail(request.email().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, toDto(user));
    }

    @Transactional
    public MessageResponse verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid or already-used verification link"));

        if (user.isEmailVerified()) {
            return new MessageResponse("Email already verified. You can sign in.");
        }

        if (user.getVerificationTokenExpiresAt() == null || user.getVerificationTokenExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Verification link expired. Request a new one.");
        }

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationCode(null);
        user.setVerificationTokenExpiresAt(null);
        userRepository.save(user);

        return new MessageResponse("Email verified. You can sign in now.");
    }

    @Transactional
    public MessageResponse verifyCode(String email, String code) {
        // One message for "no such account", "wrong code", and "expired code" alike -- same
        // reasoning as resendVerification()'s generic message, plus it denies an attacker any
        // signal about which of the ~1M six-digit codes is closer to correct.
        String invalidMessage = "Invalid or expired code.";

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, invalidMessage));

        if (user.isEmailVerified()) {
            return new MessageResponse("Email already verified. You can sign in.");
        }

        if (user.getVerificationCode() == null || !user.getVerificationCode().equals(code)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        if (user.getVerificationTokenExpiresAt() == null || user.getVerificationTokenExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Code expired. Request a new one.");
        }

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationCode(null);
        user.setVerificationTokenExpiresAt(null);
        userRepository.save(user);

        return new MessageResponse("Email verified. You can sign in now.");
    }

    @Transactional
    public MessageResponse resendVerification(String email) {
        // Deliberately constant regardless of whether the account exists, is already verified,
        // or the send below actually succeeds -- varying this message would let someone probe
        // for which emails have accounts (a classic user-enumeration leak).
        String genericMessage = "If that account exists and isn't verified yet, we've sent a new verification email.";

        userRepository.findByEmail(email.toLowerCase()).ifPresent(user -> {
            if (user.isEmailVerified()) {
                return;
            }
            String token = UUID.randomUUID().toString();
            String code = generateVerificationCode();
            user.setVerificationToken(token);
            user.setVerificationCode(code);
            user.setVerificationTokenExpiresAt(Instant.now().plusMillis(verificationTokenExpirationMs));
            userRepository.save(user);
            emailService.sendVerificationEmail(user.getEmail(), token, code);
        });

        return new MessageResponse(genericMessage);
    }

    @Transactional
    public MessageResponse forgotPassword(String email) {
        // Same anti-enumeration reasoning as resendVerification() -- a constant response
        // regardless of whether the account exists, is unverified, or the send fails.
        String genericMessage = "If that account exists, we've sent a password reset link.";

        userRepository.findByEmail(email.toLowerCase()).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setResetToken(token);
            user.setResetTokenExpiresAt(Instant.now().plusMillis(resetTokenExpirationMs));
            userRepository.save(user);
            emailService.sendPasswordResetEmail(user.getEmail(), token);
        });

        return new MessageResponse(genericMessage);
    }

    @Transactional
    public MessageResponse resetPassword(String token, String newPassword) {
        User user = userRepository.findByResetToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired reset link."));

        if (user.getResetTokenExpiresAt() == null || user.getResetTokenExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Reset link expired. Request a new one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Single-use: clear it immediately so the same link can't reset the password twice, and
        // so a token leaked after use (e.g. in a proxy/browser-history log) is already dead.
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);
        userRepository.save(user);

        return new MessageResponse("Password reset. You can sign in with your new password now.");
    }

    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        return toDto(user);
    }

    private UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getName());
    }
}
