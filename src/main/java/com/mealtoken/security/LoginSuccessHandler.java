package com.mealtoken.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Locale;

/** After login: restore the user's saved language, then continue to the originally requested page. */
@Component
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {
    private final LocaleResolver locales;

    public LoginSuccessHandler(LocaleResolver locales) {
        this.locales = locales;
        setDefaultTargetUrl("/home");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse res, Authentication auth)
            throws ServletException, IOException {
        if (auth.getPrincipal() instanceof AppUserDetails u && u.getLanguage() != null)
            locales.setLocale(req, res, Locale.forLanguageTag(u.getLanguage()));
        super.onAuthenticationSuccess(req, res, auth);
    }
}
