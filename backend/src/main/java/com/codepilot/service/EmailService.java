package com.codepilot.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Resend's own no-verification-needed sender -- works immediately, no custom domain needed. */
    private static final String RESEND_SANDBOX_FROM = "onboarding@resend.dev";

    private final JavaMailSender mailSender;
    private final WebClient resendWebClient;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.mail.provider}")
    private String provider;

    @Value("${app.mail.resend.api-key}")
    private String resendApiKey;

    @Value("${app.mail.resend.from}")
    private String resendFromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender, @Qualifier("resendWebClient") WebClient resendWebClient) {
        this.mailSender = mailSender;
        this.resendWebClient = resendWebClient;
    }

    /**
     * @return true if the email was handed off to the mail provider successfully, false if it
     * failed (logged either way). Callers that can safely reveal delivery status to the user
     * (e.g. registration, where the user already knows their own email was just used) should
     * check this and say so; callers where doing so would leak account existence to a prober
     * (e.g. "resend verification") must keep their response message constant regardless.
     */
    public boolean sendVerificationEmail(String toEmail, String token, String code) {
        String verificationLink = frontendUrl + "/verify-email?token=" + token;
        String html = """
                <p>Welcome to CodePilot!</p>
                <p>Enter this code in the app to verify your email address:</p>
                <p style="font-size: 32px; font-weight: bold; letter-spacing: 8px; font-family: monospace;">%s</p>
                <p>Or click this link instead: <a href="%s">Verify my email</a></p>
                <p>Or paste this link into your browser: %s</p>
                <p>This code and link expire in 24 hours.</p>
                """.formatted(code, verificationLink, verificationLink);
        String subject = "Verify your CodePilot email address";

        if ("resend".equalsIgnoreCase(provider)) {
            return sendViaResend(toEmail, subject, html);
        }
        return sendViaSmtp(toEmail, subject, html);
    }

    private boolean sendViaSmtp(String toEmail, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(toEmail);
            helper.setFrom(fromAddress);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send verification email to {} via SMTP", toEmail, e);
            return false;
        }
    }

    private boolean sendViaResend(String toEmail, String subject, String html) {
        // Deliberately not reusing app.mail.from here: that's the SMTP-path sender, and Resend
        // rejects (403) any "from" whose domain isn't verified with them specifically -- a real
        // inbox address like a personal Gmail one doesn't qualify just because it's a real address
        // in general. RESEND_FROM_ADDRESS defaults to Resend's own sandbox sender, which works
        // immediately with zero domain setup; override it once you've verified a domain with them.
        String from = (resendFromAddress == null || resendFromAddress.isBlank())
                ? RESEND_SANDBOX_FROM
                : resendFromAddress;
        try {
            resendWebClient.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .bodyValue(Map.of(
                            "from", from,
                            "to", List.of(toEmail),
                            "subject", subject,
                            "html", html))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Failed to send verification email to {} via Resend", toEmail, e);
            return false;
        }
    }
}
