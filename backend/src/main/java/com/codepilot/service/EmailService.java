package com.codepilot.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /**
     * @return true if the email was handed off to the mail server successfully, false if it
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

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(toEmail);
            helper.setFrom(fromAddress);
            helper.setSubject("Verify your CodePilot email address");
            helper.setText(html, true);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", toEmail, e);
            return false;
        }
    }
}
