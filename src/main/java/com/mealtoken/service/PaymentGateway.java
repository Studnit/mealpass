package com.mealtoken.service;

/** Provider abstraction for online payments. A payment is only ever marked paid from a verified server callback. */
public interface PaymentGateway {
    String provider();

    /** @return true only if {@code signature} authenticates {@code rawBody} as coming from the provider. */
    boolean verifySignature(String rawBody, String signature);
}
