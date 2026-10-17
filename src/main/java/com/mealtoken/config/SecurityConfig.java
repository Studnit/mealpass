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
                .requestMatchers("/login", "/register", "/verify-otp", "/app", "/app/**", "/forgot", "/reset/**", "/css/**", "/js/**", "/img/**", "/error",
                                 "/hotel-logo/**", "/actuator/health", "/webhooks/**", "/manifest.webmanifest", "/sw.js", "/offline.html", "/icons/**").permitAll()
                .requestMatchers("/admin/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/owner/**").hasRole("OWNER")
                .requestMatchers("/staff/**").hasRole("STAFF")
                .requestMatchers("/customer/**").hasRole("CUSTOMER")
                .anyRequest().authenticated())
            .headers(h -> h.addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                "Permissions-Policy", "camera=(self), microphone=(), geolocation=()")))
            .formLogin(f -> f
                .loginPage("/login")
                .successHandler(success)   // returns to the page the user wanted (e.g. a scanned QR URL), else /home
                .failureHandler((req, res, ex) -> {
                    if (ex instanceof org.springframework.security.authentication.DisabledException) {   // account not verified yet
                        String u = req.getParameter("username");
                        res.sendRedirect(req.getContextPath() + "/verify-otp?unverified&email="
                                + java.net.URLEncoder.encode(u == null ? "" : u.trim(), java.nio.charset.StandardCharsets.UTF_8));
                    } else res.sendRedirect(req.getContextPath() + "/login?error");
                }))
            .logout(l -> l.logoutSuccessUrl("/login?logout").deleteCookies("JSESSIONID"))
            .sessionManagement(s -> s.sessionFixation().migrateSession())
            // Webhooks are authenticated by an HMAC signature instead of a browser CSRF token.
            .csrf(c -> c.ignoringRequestMatchers(new AntPathRequestMatcher("/webhooks/**")));
        return http.build();
    }
}
