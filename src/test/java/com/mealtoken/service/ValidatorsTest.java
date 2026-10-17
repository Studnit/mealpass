package com.mealtoken.service;

import com.mealtoken.util.Validators;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorsTest {
    @Test
    void mobileMustBeTenDigitsStartingWithSixToNine() {
        assertThat(Validators.isMobile("9876543210")).isTrue();
        assertThat(Validators.isMobile("6000000000")).isTrue();
        for (String bad : new String[]{"987654321", "98765432101", "5876543210", "98765 43210", "+919876543210", "abcdefghij", "", null})
            assertThat(Validators.isMobile(bad)).as(String.valueOf(bad)).isFalse();
        assertThat(Validators.mobileOrNull("  ")).isNull();
        assertThatThrownBy(() -> Validators.mobileOrNull("12345")).hasMessage("error.phone.invalid");
    }

    @Test
    void emailMustLookLikeAnAddress() {
        assertThat(Validators.isEmail("nitesh.k+meal@example.co.in")).isTrue();
        for (String bad : new String[]{"nitesh", "nitesh@", "@example.com", "nitesh@example", "nitesh@@example.com", "a b@example.com", "nitesh@example..com", null})
            assertThat(Validators.isEmail(bad)).as(String.valueOf(bad)).isFalse();
        assertThatThrownBy(() -> Validators.requireEmail("bad")).hasMessage("error.email.invalid");
    }
}
