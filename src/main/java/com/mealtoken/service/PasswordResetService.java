package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.PasswordResetToken;
import com.mealtoken.entity.User;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.PasswordResetTokenRepository;
import com.mealtoken.repository.UserRepository;
import com.mealtoken.security.RequestRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Plug in an email/SMS implementation; without one the link is written to the server log (development only). */
    public interface Sender { void send(User user, String resetLink); }

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final RequestRateLimiter limiter;
    private final ObjectProvider<Sender> sender;
    private final Clock clock;
    private final com.mealtoken.util.PublicUrl publicUrl;

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, PasswordEncoder encoder,
                                AuditService audit, RequestRateLimiter limiter, ObjectProvider<Sender> sender, Clock clock,
                                com.mealtoken.util.PublicUrl publicUrl) {
        this.users = users; this.tokens = tokens; this.encoder = encoder; this.audit = audit;
        this.limiter = limiter; this.sender = sender; this.clock = clock; this.publicUrl = publicUrl;
    }

    /** Always behaves identically whether or not the email exists (no account enumeration). */
    @Transactional
    public void request(String email) {
        if (email == null || email.isBlank()) return;
        String mail = email.trim().toLowerCase();
        if (!limiter.tryAcquire("reset:" + mail, 5, Duration.ofHours(1).toMillis())) return;
        users.findByEmailIgnoreCase(mail).filter(u -> "ACTIVE".equals(u.getStatus())).ifPresent(u -> {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            PasswordResetToken t = new PasswordResetToken();
            t.setUserId(u.getId());
            t.setTokenHash(hash(token));
            t.setExpiresAt(clock.instant().plus(Duration.ofMinutes(30)));
            tokens.save(t);
            String link = publicUrl.url("/reset/" + token);
            Sender s = sender.getIfAvailable();
            if (s != null) s.send(u, link);
            else log.warn("PASSWORD RESET LINK for {} (no email sender configured): {}", u.getEmail(), link);
            audit.log(u.getId(), null, "PASSWORD_RESET_REQUESTED", "User", u.getId(), null);
        });
    }

    @Transactional(readOnly = true)
    public boolean isValid(String token) {
        return tokens.findByTokenHash(hash(token)).filter(t -> !t.isUsed() && t.getExpiresAt().isAfter(clock.instant())).isPresent();
    }

    @Transactional
    public void reset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) throw new BusinessException("error.password.short");
        PasswordResetToken t = tokens.findByTokenHash(hash(token))
                .filter(x -> !x.isUsed() && x.getExpiresAt().isAfter(clock.instant()))
                .orElseThrow(() -> new BusinessException("error.reset.invalid"));
        User u = users.findById(t.getUserId()).orElseThrow(() -> new BusinessException("error.reset.invalid"));
        u.setPasswordHash(encoder.encode(newPassword));
        u.setFailedLogins(0);
        t.setUsed(true);
        audit.log(u.getId(), null, "PASSWORD_RESET_COMPLETED", "User", u.getId(), null);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
