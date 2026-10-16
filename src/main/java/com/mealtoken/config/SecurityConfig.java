package com.mealtoken.config;

import com.mealtoken.security.LoginRateLimiter;
import com.mealtoken.security.LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginRateLimiter limiter, LoginSuccessHandler success) throws Exception {
        http
            .addFilterBefore(limiter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/login", "/register", "/forgot", "/reset/**", "/css/**", "/js/**", "/img/**", "/error",
                                 "/hotel-logo/**", "/actuator/health", "/webhooks/**").permitAll()
                .requestMatchers("/admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/owner/**").hasRole("OWNER")
                .requestMatchers("/staff/**").hasRole("STAFF")
                .requestMatchers("/customer/**").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            .formLogin(f -> f
                .loginPage("/login")
                .successHandler(success)   // returns to the page the user wanted (e.g. a scanned QR URL), else /home
                .failureUrl("/login?error"))
            .logout(l -> l.logoutSuccessUrl("/login?logout").deleteCookies("JSESSIONID"))
            .sessionManagement(s -> s.sessionFixation().migrateSession())
            // Webhooks are authenticated by an HMAC signature instead of a browser CSRF token.
            .csrf(c -> c.ignoringRequestMatchers(new AntPathRequestMatcher("/webhooks/**")));
        return http.build();
    }
}
