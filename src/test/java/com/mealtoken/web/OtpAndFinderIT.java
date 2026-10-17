package com.mealtoken.web;

import com.mealtoken.dto.RegistrationForm;
import com.mealtoken.entity.User;
import com.mealtoken.notification.OtpSender;
import com.mealtoken.repository.UserRepository;
import com.mealtoken.service.CustomerService;
import com.mealtoken.service.HotelService;
import com.mealtoken.service.OtpService;
import com.mealtoken.service.RegistrationService;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.otp.required=true")
@Import(OtpAndFinderIT.Capture.class)
class OtpAndFinderIT extends AbstractIntegrationTest {
    /** Captures the code instead of sending it. */
    @TestConfiguration
    static class Capture {
        static final Map<String, String> CODES = new ConcurrentHashMap<>();
        @Bean OtpSender capturingSender() {
            return new OtpSender() {
                public boolean supports(User u) { return true; }
                public void send(User u, String code, int minutes) { CODES.put(u.getEmail(), code); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired OtpService otp;
    @Autowired RegistrationService registration;
    @Autowired UserRepository users;
    @Autowired CustomerService customers;
    @Autowired MutableClock clock;

    private User newCustomer() {
        RegistrationForm f = new RegistrationForm();
        f.setAccountType("CUSTOMER");
        f.setFullName("Nitesh");
        f.setEmail(Fixtures.unique("otp") + "@test.local");
        f.setPhone("9000000000");
        f.setPassword(Fixtures.PASSWORD);
        return registration.register(f, "en");
    }

    @Test
    void unverifiedAccountCannotSignInUntilTheCodeIsEntered() throws Exception {
        User u = newCustomer();
        assertThat(u.isVerified()).isFalse();
        mvc.perform(post("/login").with(csrf()).param("username", u.getEmail()).param("password", Fixtures.PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("/verify-otp?unverified&email=*"));

        otp.issue(u);
        String code = Capture.CODES.get(u.getEmail());
        assertThat(code).matches("\\d{6}");
        otp.verify(u.getEmail(), code);
        assertThat(users.findById(u.getId()).orElseThrow().isVerified()).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", u.getEmail()).param("password", Fixtures.PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/home"));
    }

    @Test
    void wrongCodesAreCountedAndLockTheCode() {
        User u = newCustomer();
        otp.issue(u);
        String good = Capture.CODES.get(u.getEmail());
        String bad = good.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++)
            assertThatThrownBy(() -> otp.verify(u.getEmail(), bad)).hasMessage("error.otp.invalid");
        assertThatThrownBy(() -> otp.verify(u.getEmail(), good)).hasMessage("error.otp.locked");
    }

    @Test
    void codeExpiresAndResendHasACooldown() {
        User u = newCustomer();
        otp.issue(u);
        assertThatThrownBy(() -> otp.issue(u)).hasMessage("error.otp.wait");
        String code = Capture.CODES.get(u.getEmail());
        clock.advance(Duration.ofMinutes(OtpService.VALID_MINUTES + 1));
        assertThatThrownBy(() -> otp.verify(u.getEmail(), code)).hasMessage("error.otp.invalid");
        otp.issue(u);   // cooldown has passed, a fresh code works
        otp.verify(u.getEmail(), Capture.CODES.get(u.getEmail()));
    }

    @Test
    void appPageAndQrArePublicAndFinderFiltersByCityAndArea() throws Exception {
        mvc.perform(get("/app").param("city", "Pune").param("area", "Baner")).andExpect(status().isOk());
        mvc.perform(get("/app/qr.png").param("city", "Pune")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/app/poster")).andExpect(status().isOk());
        mvc.perform(get("/verify-otp")).andExpect(status().isOk());

        var w = fx.newWorld();
        // newWorld hotels have no location yet; give one a city/area through the owner profile
        var h = locate(w.owner());
        assertThat(customers.activeHotels(null, "Nowhere", null)).isEmpty();
        assertThat(customers.activeHotels(null, h.getCity(), h.getArea())).extracting(m -> m.get("id")).contains(h.getId());
        assertThat(customers.activeHotels(null, h.getCity().toUpperCase(), "no-such-area")).isEmpty();
        assertThat(customers.locations()).anyMatch(l -> h.getCity().equals(l.get("city")) && h.getArea().equals(l.get("area")));
    }

    @Autowired HotelService hotelService;

    private com.mealtoken.entity.Hotel locate(com.mealtoken.security.HotelContext ctx) {
        String city = Fixtures.unique("City"), area = Fixtures.unique("Area");
        hotelService.updateProfile(ctx, ctx.hotel().getName(), "Main road", city, area, null, null, "Asia/Kolkata", false, 5);
        var h = ctx.hotel();
        h.setCity(city); h.setArea(area);
        return h;
    }
}
