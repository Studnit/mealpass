package com.mealtoken.exception;

/** Business-rule violation. The message is an i18n message key. */
public class BusinessException extends RuntimeException {
    public BusinessException(String messageKey) { super(messageKey); }
}
