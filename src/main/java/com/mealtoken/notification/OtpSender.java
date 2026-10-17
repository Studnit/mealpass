package com.mealtoken.notification;

import com.mealtoken.entity.User;

/** Delivers a verification code over one channel (email, SMS, ...). Every available implementation is used. */
public interface OtpSender {
    /** @return true if this channel can reach the user (e.g. SMS needs a phone number). */
    boolean supports(User user);
    void send(User user, String code, int validMinutes);
}
