package com.mealtoken.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class AppConfig {
    /** Single clock bean so tests can freeze/shift time. */
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
