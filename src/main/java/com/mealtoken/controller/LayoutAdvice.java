package com.mealtoken.controller;

import com.mealtoken.notification.NotificationService;
import com.mealtoken.security.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Data every authenticated page needs: the signed-in user and the unread-notification badge. */
@ControllerAdvice
public class LayoutAdvice {
    private final NotificationService notifications;

    public LayoutAdvice(NotificationService notifications) { this.notifications = notifications; }

    @ModelAttribute("me")
    public AppUserDetails me(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof AppUserDetails u ? u : null;
    }

    @ModelAttribute("unread")
    public Long unread(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof AppUserDetails u ? notifications.unread(u.getId()) : 0L;
    }
}
