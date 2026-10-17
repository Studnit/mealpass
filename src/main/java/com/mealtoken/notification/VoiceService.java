package com.mealtoken.notification;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** Builds the short spoken announcements (read aloud by the browser) in the user's language. */
@Component
public class VoiceService {
    private final MessageSource messages;

    public VoiceService(MessageSource messages) { this.messages = messages; }

    public String say(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    /** BCP-47 tag the browser uses to pick a voice. */
    public String langTag() {
        return switch (LocaleContextHolder.getLocale().getLanguage()) {
            case "mr" -> "mr-IN";
            case "hi" -> "hi-IN";
            default -> "en-IN";
        };
    }
}
