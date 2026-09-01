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

    // Deliberately much shorter than the verification code's 24h -- a login code stands in for a
    // password on every use, not just once during signup/recovery, so it should be dead again
    // well before anyone but the requester has a realistic chance to use a copy of it (an email
    // forwarded, a shared inbox, a shoulder-surfed screen).
    @Value("${app.login-otp.token-expiration-ms}")
    private long loginCodeExpirationMs;

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
    public MessageResponse requestLoginCode(String email) {
        // Same anti-enumeration reasoning as resendVerification() -- a constant response
        // regardless of whether the account exists, is verified, or the send fails.
        String genericMessage = "If that account exists and is verified, we've sent a sign-in code.";

        userRepository.findByEmail(email.toLowerCase()).ifPresent(user -> {
            // Passwordless login is an ALTERNATIVE to password login, not a way around the
            // signup email-verification step -- it carries the same "must already be verified"
            // requirement login() enforces via DisabledException, just checked directly here
            // since there's no password to authenticate through AuthenticationManager with.
            if (!user.isEmailVerified()) {
                return;
            }
            String code = generateVerificationCode();
            user.setLoginCode(code);
            user.setLoginCodeExpiresAt(Instant.now().plusMillis(loginCodeExpirationMs));
            userRepository.save(user);
            emailService.sendLoginCodeEmail(user.getEmail(), code);
        });

        return new MessageResponse(genericMessage);
    }

    @Transactional
    public AuthResponse verifyLoginCode(String email, String code) {
        // One message for "no such account", "wrong code", and "expired code" alike -- same
        // enumeration/brute-force reasoning as verifyCode()'s identical choice.
        String invalidMessage = "Invalid or expired code.";

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, invalidMessage));

        if (user.getLoginCode() == null || !user.getLoginCode().equals(code)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, invalidMessage);
        }

        if (user.getLoginCodeExpiresAt() == null || user.getLoginCodeExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Code expired. Request a new one.");
        }

        // Single-use: clear it immediately so the same code can't sign in twice, and so a code
        // leaked after use (forwarded email, shared inbox) is already dead.
        user.setLoginCode(null);
        user.setLoginCodeExpiresAt(null);
        userRepository.save(user);

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, toDto(user));
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
