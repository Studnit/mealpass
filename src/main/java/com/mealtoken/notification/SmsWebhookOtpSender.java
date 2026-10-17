package com.mealtoken.notification;

import com.mealtoken.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends the code to the user's mobile through any SMS / WhatsApp gateway that accepts a JSON webhook
 * ({"to": "...", "message": "..."}, optional bearer token). Active only when app.otp.sms-webhook-url is set.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${app.otp.sms-webhook-url:}'.isEmpty()")
public class SmsWebhookOtpSender implements OtpSender {
    private static final Logger log = LoggerFactory.getLogger(SmsWebhookOtpSender.class);
    private final String url;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SmsWebhookOtpSender(@Value("${app.otp.sms-webhook-url}") String url, @Value("${app.otp.sms-webhook-token:}") String token) {
        this.url = url; this.token = token;
    }

    @Override public boolean supports(User u) { return u.getPhone() != null && !u.getPhone().isBlank(); }

    @Override public void send(User u, String code, int minutes) {
        try {
            String msg = "Your verification code is " + code + ". Valid for " + minutes + " minutes.";
            String body = "{\"to\":\"" + json(u.getPhone()) + "\",\"message\":\"" + json(msg) + "\"}";
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (!token.isBlank()) b.header("Authorization", "Bearer " + token);
            HttpResponse<Void> r = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() >= 300) log.warn("SMS gateway answered {} for user {}", r.statusCode(), u.getId());
        } catch (Exception e) {
            log.warn("SMS delivery failed for user {}: {}", u.getId(), e.toString());
        }
    }

    private static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "); }
}
