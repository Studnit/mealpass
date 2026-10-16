package com.mealtoken.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.service.PaymentGateway;
import com.mealtoken.service.PaymentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Server-to-server payment callbacks. A browser redirect can never mark a payment paid: only a body whose
 * HMAC signature verifies is accepted. Disabled (404) until APP_PAYMENT_WEBHOOK_SECRET is configured.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    private final ObjectProvider<PaymentGateway> gateway;
    private final PaymentService payments;
    private final ObjectMapper json;

    public WebhookController(ObjectProvider<PaymentGateway> gateway, PaymentService payments, ObjectMapper json) {
        this.gateway = gateway; this.payments = payments; this.json = json;
    }

    @PostMapping("/payments")
    public ResponseEntity<Map<String, Object>> payment(@RequestBody String body,
                                                       @RequestHeader(value = "X-Signature", required = false) String signature) {
        PaymentGateway g = gateway.getIfAvailable();
        if (g == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (!g.verifySignature(body, signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false));
        try {
            JsonNode n = json.readTree(body);
            long paymentId = n.path("paymentId").asLong(-1);
            String status = n.path("status").asText("");
            if (paymentId < 0 || !(status.equals("SUCCESS") || status.equals("FAILED")))
                return ResponseEntity.badRequest().body(Map.of("ok", false));
            BigDecimal amount = n.hasNonNull("amount") ? new BigDecimal(n.get("amount").asText()) : null;
            payments.applyGatewayResult(paymentId, g.provider(), n.path("reference").asText(null), status.equals("SUCCESS"), amount);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (BusinessException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false));
        }
    }
}
