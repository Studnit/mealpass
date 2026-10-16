package com.mealtoken.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final LanguagePreferenceInterceptor languagePreference;

    public WebConfig(LanguagePreferenceInterceptor languagePreference) { this.languagePreference = languagePreference; }

    @Bean
    LocaleResolver localeResolver() {
        CookieLocaleResolver r = new CookieLocaleResolver("lang");
        r.setDefaultLocale(Locale.ENGLISH);
        r.setCookieMaxAge(Duration.ofDays(365));
        return r;
    }

    @Bean
    MessageSource messageSource() {
        ReloadableResourceBundleMessageSource m = new ReloadableResourceBundleMessageSource();
        m.setBasename("classpath:messages");
        m.setDefaultEncoding("UTF-8");
        m.setFallbackToSystemLocale(false);
        return m;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor i = new LocaleChangeInterceptor();
        i.setParamName("lang");
        registry.addInterceptor(i);
        registry.addInterceptor(languagePreference);
    }
}
