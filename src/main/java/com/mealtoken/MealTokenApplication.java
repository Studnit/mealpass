package com.mealtoken;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MealTokenApplication {
    public static void main(String[] args) {
        SpringApplication.run(MealTokenApplication.class, args);
    }
}
