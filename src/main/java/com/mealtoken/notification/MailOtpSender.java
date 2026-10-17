package com.mealtoken.notification;

import com.mealtoken.entity.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Sends the code by email. Active only when spring.mail.host (env MAIL_HOST) is set. */
@Component
@ConditionalOnProperty("spring.mail.host")
public class MailOtpSender implements OtpSender {
    private final JavaMailSender mail;
    private final String from;

    public MailOtpSender(JavaMailSender mail, @Value("${app.mail.from:no-reply@localhost}") String from) { this.mail = mail; this.from = from; }

    @Override public boolean supports(User u) { return u.getEmail() != null && !u.getEmail().isBlank(); }

    @Override public void send(User u, String code, int minutes) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(u.getEmail());
        m.setSubject("Your verification code: " + code);
        m.setText("Hello " + u.getFullName() + ",\n\nYour verification code is " + code + ". It is valid for " + minutes
                + " minutes. If you did not create an account, ignore this message.");
        mail.send(m);
    }
}
