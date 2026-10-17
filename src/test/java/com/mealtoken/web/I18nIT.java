package com.mealtoken.web;

import com.mealtoken.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class I18nIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MessageSource messages;

    @Test
    void loginPageRendersInEachLanguage() throws Exception {
        for (String lang : new String[]{"en", "mr", "hi"}) {
            String html = mvc.perform(get("/login").param("lang", lang)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).doesNotContain("??");           // Spring's marker for a missing key
        }
        String mr = mvc.perform(get("/login").param("lang", "mr")).andReturn().getResponse().getContentAsString();
        String en = mvc.perform(get("/login").param("lang", "en")).andReturn().getResponse().getContentAsString();
        assertThat(mr).isNotEqualTo(en).containsPattern("[\\u0900-\\u097F]");
    }

    @Test
    void everyEnglishKeyHasMarathiAndHindiTranslations() throws Exception {
        var en = load("messages.properties");
        for (String f : new String[]{"messages_mr.properties", "messages_hi.properties"}) {
            var other = load(f);
            assertThat(other.stringPropertyNames()).as(f).containsAll(en.stringPropertyNames());
        }
        assertThat(messages.getMessage(en.stringPropertyNames().iterator().next(), null, Locale.forLanguageTag("mr"))).isNotBlank();
    }

    private static java.util.Properties load(String name) throws Exception {
        var p = new java.util.Properties();
        try (var in = new java.io.InputStreamReader(I18nIT.class.getClassLoader().getResourceAsStream(name), java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }
}
