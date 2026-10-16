package com.mealtoken.util;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Locale- and timezone-aware formatting helpers, exposed to templates as {@code @fmt}. */
@Component("fmt")
public class Fmt {

    private Locale locale() {
        String lang = LocaleContextHolder.getLocale().getLanguage();
        return new Locale(lang.isEmpty() ? "en" : lang, "IN");
    }

    public String money(Object amount) {
        if (amount == null) return "";
        BigDecimal v = amount instanceof BigDecimal b ? b : new BigDecimal(amount.toString());
        return NumberFormat.getCurrencyInstance(new Locale("en", "IN")).format(v);
    }

    public String date(Object value) {
        LocalDate d;
        if (value == null) return "";
        else if (value instanceof LocalDate ld) d = ld;
        else if (value instanceof java.sql.Date sd) d = sd.toLocalDate();
        else d = LocalDate.parse(value.toString());
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()).format(d);
    }

    public String dateTime(Instant t, String zone) {
        if (t == null) return "";
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale()).format(t.atZone(ZoneId.of(zone == null ? "Asia/Kolkata" : zone)));
    }
}
