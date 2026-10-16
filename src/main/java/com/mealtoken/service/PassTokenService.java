package com.mealtoken.service;

import com.mealtoken.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * Short-lived, HMAC-signed customer pass shown as a QR for the staff-operated workflow.
 * Format: base64url("userId.expiryEpochSec") + "." + base64url(HMAC-SHA256). It contains no secrets or balances.
 */
@Service
public class PassTokenService {
    static final long TTL_SECONDS = 300;
    private final byte[] key;
    private final Clock clock;

    public PassTokenService(@Value("${app.security.qr-secret}") String secret, Clock clock) {
        if (secret == null || secret.length() < 16) throw new IllegalStateException("app.security.qr-secret must be at least 16 characters");
        if (secret.startsWith("dev-only-"))
            org.slf4j.LoggerFactory.getLogger(PassTokenService.class).warn("QR_SECRET is the development default; set a unique secret in production");
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String issue(Long userId) {
        long exp = clock.instant().getEpochSecond() + TTL_SECONDS;
        String body = b64((userId + "." + exp).getBytes(StandardCharsets.UTF_8));
        return body + "." + b64(hmac(body));
    }

    /** @return the customer id, or throws if the token is malformed, forged, or expired. */
    public Long verify(String token) {
        try {
            String[] p = token.trim().split("\\.");
            if (p.length != 2) throw new BusinessException("error.pass.invalid");
            if (!MessageDigest.isEqual(hmac(p[0]), Base64.getUrlDecoder().decode(p[1])))
                throw new BusinessException("error.pass.invalid");
            String[] body = new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8).split("\\.");
            if (Long.parseLong(body[1]) < clock.instant().getEpochSecond()) throw new BusinessException("error.pass.expired");
            return Long.parseLong(body[0]);
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new BusinessException("error.pass.invalid");
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
