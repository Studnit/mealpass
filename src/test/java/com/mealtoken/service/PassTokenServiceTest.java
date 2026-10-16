package com.mealtoken.service;

import com.mealtoken.exception.BusinessException;
import com.mealtoken.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassTokenServiceTest {
    private static final String SECRET = "unit-test-secret-0123456789";

    private final MutableClock clock = new MutableClock();
    private final PassTokenService svc = new PassTokenService(SECRET, clock);

    @Test
    void roundTripReturnsTheSameUser() {
        assertThat(svc.verify(svc.issue(42L))).isEqualTo(42L);
    }

    @Test
    void expiresAfterFiveMinutes() {
        String t = svc.issue(7L);
        clock.advance(Duration.ofMinutes(4));
        assertThat(svc.verify(t)).isEqualTo(7L);
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> svc.verify(t)).isInstanceOf(BusinessException.class).hasMessage("error.pass.expired");
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        String t = svc.issue(7L);
        String[] p = t.split("\\.");
        String forgedBody = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("8.9999999999".getBytes());
        assertThatThrownBy(() -> svc.verify(forgedBody + "." + p[1])).hasMessage("error.pass.invalid");
        assertThatThrownBy(() -> svc.verify("garbage")).hasMessage("error.pass.invalid");
        assertThatThrownBy(() -> svc.verify("a.b.c")).hasMessage("error.pass.invalid");
        String other = new PassTokenService("another-secret-0123456789", clock).issue(7L);
        assertThatThrownBy(() -> svc.verify(other)).hasMessage("error.pass.invalid");
    }

    @Test
    void weakSecretIsRefused() {
        assertThatThrownBy(() -> new PassTokenService("short", clock)).isInstanceOf(IllegalStateException.class);
    }
}
