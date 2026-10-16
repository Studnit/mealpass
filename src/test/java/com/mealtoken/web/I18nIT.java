package com.mealtoken.web;

import com.mealtoken.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;
import java.util.Properties;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class I18nIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MessageSource messages;

    @Test
    void loginPageRendersInEachLanguage() throws Exception {
        for (String lang : new String[]{"en", "mr", "hi"}) {
            String html = mvc.perform(
                    get("/login").param("lang", lang)
            ).andExpect(status().isOk())
             .andReturn()
             .getResponse()
             .getContentAsString();

            assertThat(html).doesNotContain("??");
        }

        String mr = mvc.perform(
                get("/login").param("lang", "mr")
        ).andReturn().getResponse().getContentAsString();

        String en = mvc.perform(
                get("/login").param("lang", "en")
        ).andReturn().getResponse().getContentAsString();

        assertThat(mr)
                .isNotEqualTo(en)
                .containsPattern("[\\u0900-\\u097F]");
    }

    @Test
    void everyEnglishKeyHasMarathiAndHindiTranslations() throws Exception {

        Properties en = load("messages.properties");

        for (String file : new String[]{
                "messages_mr.properties",
                "messages_hi.properties"
        }) {
            Properties other = load(file);

            assertThat(other.stringPropertyNames())
                    .as(file)
                    .containsAll(en.stringPropertyNames());
        }

        String firstKey = en.stringPropertyNames()
                .iterator()
                .next();

        assertThat(
                messages.getMessage(
                        firstKey,
                        null,
                        Locale.forLanguageTag("mr")
                )
        ).isNotBlank();
    }

    private static Properties load(String name) throws Exception {
        Properties properties = new Properties();

        var resource = I18nIT.class.getClassLoader()
                .getResourceAsStream(name);

        if (resource == null) {
            throw new IllegalStateException(
                    "Missing message properties file: " + name
            );
        }

        try (var reader = new InputStreamReader(
                resource,
                StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        }

        return properties;
    }
}