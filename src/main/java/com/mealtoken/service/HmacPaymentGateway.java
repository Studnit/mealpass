package com.mealtoken.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Generic webhook verifier: provider signs the raw body with HMAC-SHA256 using a shared secret
 * (hex in the X-Signature header). Enabled only when APP_PAYMENT_WEBHOOK_SECRET is configured.
 * Replace with a provider-specific implementation (Razorpay, Stripe, ...) as needed.
 */
@Component
@ConditionalOnProperty(name = "app.payment.webhook-secret")
public class HmacPaymentGateway implements PaymentGateway {
    private final byte[] secret;
    private final String provider;

    public HmacPaymentGateway(@Value("${app.payment.webhook-secret}") String secret,
                              @Value("${app.payment.provider:generic}") String provider) {
        if (secret.length() < 16) throw new IllegalStateException("app.payment.webhook-secret must be at least 16 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.provider = provider;
    }

    @Override public String provider() { return provider; }

    @Override
    public boolean verifySignature(String rawBody, String signature) {
        if (rawBody == null || signature == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature.trim()));
        } catch (Exception e) { return false; }
    }
}
