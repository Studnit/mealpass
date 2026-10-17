package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.OtpCode;
import com.mealtoken.entity.User;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.notification.OtpSender;
import com.mealtoken.repository.OtpCodeRepository;
import com.mealtoken.repository.UserRepository;
import com.mealtoken.security.RequestRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Six digit verification codes sent to the user's email and/or mobile. Codes are stored only as an HMAC, expire after
 * 10 minutes, allow 5 wrong guesses and can be re-sent at most once a minute (5 per hour).
 */
@Service
public class OtpService {
    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int VALID_MINUTES = 10;
    public static final int MAX_ATTEMPTS = 5;
    public static final int COOLDOWN_SECONDS = 60;

    private final OtpCodeRepository codes;
    private final UserRepository users;
    private final org.springframework.beans.factory.ObjectProvider<OtpSender> senders;
    private final RequestRateLimiter limiter;
    private final AuditService audit;
    private final Clock clock;
    private final byte[] secret;
    private final boolean required;

    public OtpService(OtpCodeRepository codes, UserRepository users, org.springframework.beans.factory.ObjectProvider<OtpSender> senders, RequestRateLimiter limiter,
                      AuditService audit, Clock clock, @Value("${app.security.qr-secret}") String secret,
                      @Value("${app.otp.enabled:true}") boolean required) {
        this.codes = codes; this.users = users; this.senders = senders; this.limiter = limiter; this.audit = audit;
        this.clock = clock; this.secret = secret.getBytes(StandardCharsets.UTF_8); this.required = required;
    }

    public boolean isRequired() { return required; }

    /** Creates and sends a new code. Silent for unknown or already verified emails (no account enumeration). */
    @Transactional(noRollbackFor = BusinessException.class)
    public void sendFor(String email) {
        if (email == null || email.isBlank()) return;
        users.findByEmailIgnoreCase(email.trim()).filter(u -> !u.isVerified()).ifPresent(this::issue);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void issue(User u) {
        var last = codes.findFirstByUserIdOrderByIdDesc(u.getId());
        if (last.isPresent() && last.get().getCreatedAt().plusSeconds(COOLDOWN_SECONDS).isAfter(clock.instant()))
            throw new BusinessException("error.otp.wait");
        if (!limiter.tryAcquire("otp:" + u.getId(), 5, Duration.ofHours(1).toMillis())) throw new BusinessException("error.otp.limit");
        codes.findFirstByUserIdAndConsumedAtIsNullOrderByIdDesc(u.getId()).ifPresent(o -> o.setConsumedAt(clock.instant()));
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        OtpCode o = new OtpCode();
        o.setUserId(u.getId());
        o.setCodeHash(hash(u.getId(), code));
        o.setExpiresAt(clock.instant().plus(Duration.ofMinutes(VALID_MINUTES)));
        o.setCreatedAt(clock.instant());
        codes.save(o);
        boolean sent = false;
        for (OtpSender s : senders.orderedStream().toList()) {   // none configured is fine: the code is logged instead
            if (!s.supports(u)) continue;
            try { s.send(u, code, VALID_MINUTES); sent = true; } catch (Exception e) { log.warn("OTP delivery failed for user {}: {}", u.getId(), e.toString()); }
        }
        if (!sent) log.warn("VERIFICATION CODE for {} (no email/SMS channel is configured): {}", u.getEmail(), code);
        audit.log(u.getId(), null, "OTP_SENT", "User", u.getId(), null);
    }

    /** Marks the account verified when the code matches. Wrong guesses are counted even though the call fails. */
    @Transactional(noRollbackFor = BusinessException.class)
    public User verify(String email, String code) {
        User u = users.findByEmailIgnoreCase(email == null ? "" : email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException("error.otp.invalid"));
        if (u.isVerified()) return u;
        OtpCode o = codes.findFirstByUserIdAndConsumedAtIsNullOrderByIdDesc(u.getId())
                .filter(x -> x.getExpiresAt().isAfter(clock.instant()))
                .orElseThrow(() -> new BusinessException("error.otp.invalid"));
        if (o.getAttempts() >= MAX_ATTEMPTS) throw new BusinessException("error.otp.locked");
        String given = code == null ? "" : code.trim();
        if (!MessageDigest.isEqual(hash(u.getId(), given).getBytes(StandardCharsets.UTF_8), o.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            o.setAttempts(o.getAttempts() + 1);
            throw new BusinessException("error.otp.invalid");
        }
        o.setConsumedAt(clock.instant());
        u.setVerifiedAt(clock.instant());
        audit.log(u.getId(), null, "ACCOUNT_VERIFIED", "User", u.getId(), null);
        return u;
    }

    private String hash(Long userId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
