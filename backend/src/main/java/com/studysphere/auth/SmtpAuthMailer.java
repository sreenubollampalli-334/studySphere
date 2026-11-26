package com.studysphere.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class SmtpAuthMailer implements AuthMailer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpAuthMailer.class);

    private final ObjectProvider<JavaMailSender> mailSenders;
    private final String from;

    SmtpAuthMailer(
            ObjectProvider<JavaMailSender> mailSenders,
            @Value("${studysphere.auth.mail-from:StudySphere <no-reply@example.test>}") String from
    ) {
        this.mailSenders = mailSenders;
        this.from = from;
    }

    @Override
    public void sendVerification(String email, String link) {
        send(email, "Verify your StudySphere email",
                "Open this link to verify your StudySphere account. It expires in 24 hours.\n\n"
                        + link + "\n\nIf you did not sign up for StudySphere, ignore this message.");
    }

    @Override
    public void sendPasswordReset(String email, String link) {
        send(email, "Reset your StudySphere password",
                "Open this link to reset your StudySphere password. It expires in one hour.\n\n"
                        + link + "\n\nIf you did not request this change, ignore this message.");
    }

    private void send(String email, String subject, String body) {
        JavaMailSender sender = mailSenders.getIfAvailable();
        if (sender == null) {
            LOGGER.warn("StudySphere authentication mail is unavailable because SMTP is not configured");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);

        try {
            sender.send(message);
        } catch (MailException failure) {
            LOGGER.warn("StudySphere could not deliver an authentication email");
        }
    }
}
