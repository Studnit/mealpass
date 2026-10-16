package com.mealtoken.controller;

import com.mealtoken.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;

/**
 * Turns rule violations into a flash error message on the page the user came from (Post/Redirect/Get).
 * Message keys are resolved in the user's language by the templates.
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, HttpServletRequest req) { return back(req, e.getMessage()); }

    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public String conflict(Exception e, HttpServletRequest req) {
        log.warn("Data conflict on {}: {}", req.getRequestURI(), e.getMessage());
        return back(req, "error.conflict");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            java.time.format.DateTimeParseException.class, NumberFormatException.class})
    public String badInput(Exception e, HttpServletRequest req) { return back(req, "error.input.invalid"); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooBig(HttpServletRequest req) { return back(req, "error.logo.invalid"); }

    private String back(HttpServletRequest req, String key) {
        FlashMap fm = RequestContextUtils.getOutputFlashMap(req);
        if (fm != null) fm.put("err", key);
        return "redirect:" + safeReferer(req);
    }

    /** Same-origin path only, so a forged Referer can never cause an open redirect. */
    static String safeReferer(HttpServletRequest req) {
        String ref = req.getHeader("Referer");
        try {
            if (ref != null) {
                URI u = URI.create(ref);
                if (u.getHost() != null && u.getHost().equalsIgnoreCase(req.getServerName()) && u.getRawPath() != null
                        && u.getRawPath().startsWith("/") && !u.getRawPath().startsWith("//")) {
                    return u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
                }
            }
        } catch (IllegalArgumentException ignored) { /* fall through */ }
        return "/home";
    }
}
