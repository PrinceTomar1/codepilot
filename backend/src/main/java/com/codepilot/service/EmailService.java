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

    private final JavaMailSender mailSender;
    private final WebClient sendgridWebClient;

    @Value("${app.mail.from}")
    private String fromAddress;

    /** "smtp" (local dev, Mailpit) or "sendgrid" (production) -- see application.yml for why. */
    @Value("${app.mail.provider}")
    private String provider;

    @Value("${app.mail.sendgrid.api-key}")
    private String sendgridApiKey;

    @Value("${app.mail.sendgrid.from}")
    private String sendgridFromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender, @Qualifier("sendgridWebClient") WebClient sendgridWebClient) {
        this.mailSender = mailSender;
        this.sendgridWebClient = sendgridWebClient;
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
        String text = """
                Welcome to CodePilot!

                Enter this code in the app to verify your email address: %s

                Or open this link instead: %s

                This code and link expire in 24 hours.
                """.formatted(code, verificationLink);
        String subject = "Verify your CodePilot email address";
        return send(toEmail, subject, text, html);
    }

    /**
     * @return same contract as {@link #sendVerificationEmail} -- true on hand-off success, logged
     * either way. requestLoginCode() always returns its own generic message regardless of this
     * result, for the same account-enumeration reason resendVerification() does.
     */
    public boolean sendLoginCodeEmail(String toEmail, String code) {
        String html = """
                <p>Use this code to sign in to CodePilot:</p>
                <p style="font-size: 32px; font-weight: bold; letter-spacing: 8px; font-family: monospace;">%s</p>
                <p>This code expires in 10 minutes. If you didn't request this, you can safely
                ignore this email -- no one can sign in without it.</p>
                """.formatted(code);
        String text = """
                Use this code to sign in to CodePilot: %s

                This code expires in 10 minutes. If you didn't request this, you can safely
                ignore this email -- no one can sign in without it.
                """.formatted(code);
        String subject = "Your CodePilot sign-in code";
        return send(toEmail, subject, text, html);
    }

    // Real problem, hit live: an HTML-only email (no plain-text alternative) is a well-known
    // spam signal -- Gmail routed the password-reset email (contains a clickable link) to spam
    // while the sign-in-code email (bare code, no link) reached the inbox fine from the exact
    // same sender/pipeline, minutes apart. Every send now includes a real plain-text part
    // (multipart/alternative), which meaningfully improves inbox placement across mail providers
    // regardless of content -- not just for the one template that happened to get caught.
    private boolean send(String toEmail, String subject, String text, String html) {
        if ("sendgrid".equalsIgnoreCase(provider)) {
            return sendViaSendgrid(toEmail, subject, text, html);
        }
        return sendViaSmtp(toEmail, subject, text, html);
    }

    private boolean sendViaSmtp(String toEmail, String subject, String text, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(toEmail);
            helper.setFrom(fromAddress);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send email to {} via SMTP", toEmail, e);
            return false;
        }
    }

    private boolean sendViaSendgrid(String toEmail, String subject, String text, String html) {
        // sendgridFromAddress must be exactly the address verified as a Single Sender in
        // SendGrid's dashboard (Settings -> Sender Authentication) -- SendGrid 403s anything else.
        // Unlike some providers' sandbox senders, a verified Single Sender can send to ANY
        // recipient with no domain/DNS setup required, 100 emails/day free.
        if (sendgridFromAddress == null || sendgridFromAddress.isBlank()) {
            log.error("Cannot send via SendGrid: app.mail.sendgrid.from (SENDGRID_FROM_ADDRESS) is not set");
            return false;
        }
        try {
            sendgridWebClient.post()
                    .uri("/v3/mail/send")
                    .header("Authorization", "Bearer " + sendgridApiKey)
                    .bodyValue(Map.of(
                            "personalizations", List.of(Map.of("to", List.of(Map.of("email", toEmail)))),
                            "from", Map.of("email", sendgridFromAddress),
                            "subject", subject,
                            // Plain text first, then HTML -- SendGrid (like standard MIME
                            // multipart/alternative) expects the least-rich representation first.
                            "content", List.of(
                                    Map.of("type", "text/plain", "value", text),
                                    Map.of("type", "text/html", "value", html))))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.error("Failed to send email to {} via SendGrid", toEmail, e);
            return false;
        }
    }
}
