package com.mealtoken.notification;

/**
 * Extension point for email / SMS / WhatsApp. Nothing is configured by default; provide your own
 * {@code @Component} implementing this interface (credentials via environment variables) to enable it.
 */
public interface ExternalNotificationSender {
    void send(Long userId, String type, String encodedMessage);
}
