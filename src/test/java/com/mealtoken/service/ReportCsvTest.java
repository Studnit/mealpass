package com.mealtoken.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportCsvTest {
    @Test
    void quotesAndEscapesEmbeddedQuotesAndCommas() {
        assertThat(ReportService.esc("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"");
        assertThat(ReportService.esc(null)).isEqualTo("\"\"");
    }

    @Test
    void neutralisesSpreadsheetFormulas() {
        assertThat(ReportService.esc("=HYPERLINK(\"http://x\")")).startsWith("\"'=");
        assertThat(ReportService.esc("+1+1")).startsWith("\"'+");
        assertThat(ReportService.esc("@SUM(A1)")).startsWith("\"'@");
        assertThat(ReportService.esc("-cmd")).startsWith("\"'-");
    }

    @Test
    void plainNumbersAndTextAreUntouched() {
        assertThat(ReportService.esc("-12.50")).isEqualTo("\"-12.50\"");
        assertThat(ReportService.esc("Lunch")).isEqualTo("\"Lunch\"");
    }
}
