package com.mealtoken.notification;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** Renders a stored notification ("key|arg|~argKey") in the current locale. Exposed as {@code @notificationText}. */
@Component("notificationText")
public class NotificationText {
    private final MessageSource messages;

    public NotificationText(MessageSource messages) { this.messages = messages; }

    public String render(String stored) {
        String[] parts = stored.split("\\|", -1);
        Object[] args = new Object[Math.max(parts.length - 1, 0)];
        for (int i = 1; i < parts.length; i++) {
            String a = parts[i];
            args[i - 1] = a.startsWith("~")
                    ? messages.getMessage(a.substring(1), null, a.substring(1), LocaleContextHolder.getLocale())
                    : a;
        }
        return messages.getMessage(parts[0], args, parts[0], LocaleContextHolder.getLocale());
    }
}
