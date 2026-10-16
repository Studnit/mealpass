package com.mealtoken.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The single place that knows the public address of the app. It comes from the property
 * app.public-base-url (env PUBLIC_BASE_URL) and is read on every call, so a domain change needs only a config change
 * and restart - nothing is stored in the database. If the property is empty, the address of the current request is used.
 */
@Component
public class PublicUrl {
    private final String configured;

    public PublicUrl(@Value("${app.public-base-url:}") String configured) {
        String c = configured == null ? "" : configured.trim();
        this.configured = c.endsWith("/") ? c.substring(0, c.length() - 1) : c;
    }

    public String base() {
        if (!configured.isEmpty()) return configured;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest r = a.getRequest();
            return ServletUriComponentsBuilder.fromContextPath(r).build().toUriString();
        }
        throw new IllegalStateException("app.public-base-url is not set and there is no current request");
    }

    public String url(String path) { return base() + (path.startsWith("/") ? path : "/" + path); }
}
