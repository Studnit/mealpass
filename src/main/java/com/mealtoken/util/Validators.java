package com.mealtoken.util;

import com.mealtoken.exception.BusinessException;

import java.util.regex.Pattern;

/** Shared input rules: a 10 digit Indian mobile number (starts with 6-9) and a well-formed email address. */
public final class Validators {
    public static final String MOBILE_REGEX = "^[6-9][0-9]{9}$";
    public static final String EMAIL_REGEX = "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$";
    private static final Pattern MOBILE = Pattern.compile(MOBILE_REGEX);
    private static final Pattern EMAIL = Pattern.compile(EMAIL_REGEX);

    private Validators() {}

    public static boolean isMobile(String s) { return s != null && MOBILE.matcher(s.trim()).matches(); }
    public static boolean isEmail(String s) { return s != null && s.trim().length() <= 190 && EMAIL.matcher(s.trim()).matches(); }

    /** Blank becomes null; anything else must be a valid 10 digit mobile number. */
    public static String mobileOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        if (!isMobile(s)) throw new BusinessException("error.phone.invalid");
        return s.trim();
    }

    public static String requireEmail(String s) {
        if (!isEmail(s)) throw new BusinessException("error.email.invalid");
        return s.trim();
    }
}
