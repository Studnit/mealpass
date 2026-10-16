package com.mealtoken.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the production clock with one tests can advance. A MutableClock is a Clock, so every service receives it. */
@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    MutableClock testClock() { return new MutableClock(); }
}
