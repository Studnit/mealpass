package com.mealtoken.config;

import com.mealtoken.entity.User;
import com.mealtoken.repository.RoleRepository;
import com.mealtoken.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the initial Super Admin from environment configuration if none exists. */
@Component
@Order(1)
public class BootstrapDataLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapDataLoader.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    public BootstrapDataLoader(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
                               @Value("${app.bootstrap.admin-email}") String adminEmail,
                               @Value("${app.bootstrap.admin-password}") String adminPassword) {
        this.users = users; this.roles = roles; this.encoder = encoder;
        this.adminEmail = adminEmail; this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByEmailIgnoreCase(adminEmail)) return;
        User u = new User();
        u.setFullName("Platform Administrator");
        u.setEmail(adminEmail.toLowerCase());
        u.setPasswordHash(encoder.encode(adminPassword));
        u.getRoles().add(roles.findByName("SUPER_ADMIN").orElseThrow());
        users.save(u);
        log.warn("Created bootstrap Super Admin '{}'. Change its password immediately.", adminEmail);
    }
}
