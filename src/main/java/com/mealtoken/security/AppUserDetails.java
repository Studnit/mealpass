package com.mealtoken.security;

import com.mealtoken.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.stream.Collectors;

public class AppUserDetails implements UserDetails {
    private final Long id;
    private final String email;
    private final String fullName;
    private final String passwordHash;
    private final String language;
    private final boolean active;
    private final boolean verified;
    private final Collection<? extends GrantedAuthority> authorities;

    public AppUserDetails(User u) {
        this.id = u.getId();
        this.email = u.getEmail();
        this.fullName = u.getFullName();
        this.passwordHash = u.getPasswordHash();
        this.language = u.getPreferredLanguage();
        this.active = "ACTIVE".equals(u.getStatus());
        this.verified = u.isVerified();
        this.authorities = u.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.getName()))
                .collect(Collectors.toSet());
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getLanguage() { return language; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return verified; }
}
