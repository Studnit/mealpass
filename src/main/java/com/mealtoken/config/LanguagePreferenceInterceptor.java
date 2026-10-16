package com.mealtoken.config;

import com.mealtoken.repository.UserRepository;
import com.mealtoken.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/** Persists a language switch (?lang=xx) to the signed-in user's profile so it survives future logins. */
@Component
public class LanguagePreferenceInterceptor implements HandlerInterceptor {
    private static final Set<String> SUPPORTED = Set.of("en", "mr", "hi");
    private final UserRepository users;

    public LanguagePreferenceInterceptor(UserRepository users) { this.users = users; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String lang = req.getParameter("lang");
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (lang != null && SUPPORTED.contains(lang) && a != null && a.getPrincipal() instanceof AppUserDetails u) {
            users.findById(u.getId()).ifPresent(x -> {
                if (!lang.equals(x.getPreferredLanguage())) { x.setPreferredLanguage(lang); users.save(x); }
            });
        }
        return true;
    }
}
