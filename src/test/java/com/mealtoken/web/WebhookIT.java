package com.mealtoken.web;

import com.mealtoken.entity.Subscription;
import com.mealtoken.entity.SubscriptionRequest;
import com.mealtoken.repository.SubscriptionRepository;
import com.mealtoken.service.PaymentService;
import com.mealtoken.service.SubscriptionService;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebhookIT extends AbstractIntegrationTest {
    private static final String SECRET = "webhook-test-secret-123456";   // matches application-test.yml

    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired SubscriptionService subscriptions;
    @Autowired PaymentService payments;
    @Autowired SubscriptionRepository subs;

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private Object[] pending() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        SubscriptionRequest r = subscriptions.request(c, w.hotel().getId(), w.planA().getId());
        Subscription s = subscriptions.approve(w.owner(), r.getId());
        return new Object[]{s, payments.findPendingIdFor(s.getId())};
    }

    @Test
    void unsignedOrBadlySignedCallbacksAreRejectedAndChangeNothing() throws Exception {
        Object[] p = pending();
        String body = "{\"paymentId\":" + p[1] + ",\"status\":\"SUCCESS\",\"amount\":\"100.00\",\"reference\":\"R1\"}";
        mvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).header("X-Signature", "deadbeef").content(body))
                .andExpect(status().isUnauthorized());
        assertThat(subs.findById(((Subscription) p[0]).getId()).orElseThrow().getStatus()).isNotEqualTo("ACTIVE");
    }

    @Test
    void validSignatureActivatesOnce_andReplayDoesNotCreditTwice() throws Exception {
        Object[] p = pending();
        Subscription s = (Subscription) p[0];
        String body = "{\"paymentId\":" + p[1] + ",\"status\":\"SUCCESS\",\"amount\":\"100.00\",\"reference\":\"R2\"}";
        for (int i = 0; i < 2; i++)
            mvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).header("X-Signature", sign(body)).content(body))
                    .andExpect(status().isOk());
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(after.getRemainingTokens()).isEqualTo(30);
    }

    @Test
    void wrongAmountIsRefused() throws Exception {
        Object[] p = pending();
        String body = "{\"paymentId\":" + p[1] + ",\"status\":\"SUCCESS\",\"amount\":\"1.00\",\"reference\":\"R3\"}";
        mvc.perform(post("/webhooks/payments").contentType(MediaType.APPLICATION_JSON).header("X-Signature", sign(body)).content(body))
                .andExpect(status().isUnprocessableEntity());
        assertThat(subs.findById(((Subscription) p[0]).getId()).orElseThrow().getStatus()).isNotEqualTo("ACTIVE");
    }
}
