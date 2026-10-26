package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR 0035: the CSV of the exports keeps every value a text cell (ENH-08, chapter 10 §4: a value a
 * spreadsheet would run as a formula gets an apostrophe), and the pasted list of a bulk tracking
 * change (ENH-15) is read as codes separated by spaces, commas or semicolons, each once.
 */
@Tag("ENH-08")
@Tag("ENH-15")
class OfficeToolsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {"=HYPERLINK(\"http://x.example\",\"clic\")", "+39 333", "-2+3", "@SUM(A1)", "\tTab", "\rRitorno"
            })
    void aValueASpreadsheetWouldRunStaysText(String value) {
        assertThat(CsvFile.cell(value)).isEqualTo("'" + value);
    }

    @Test
    void cellsAreQuotedSeparatedBySemicolonsAndTheFileHasABom() {
        byte[] bytes = new CsvFile()
                .row(List.of("Ragione sociale", "Città"))
                .row(Arrays.asList("Ditta \"Uno\"; S.r.l.", null))
                .row(List.of("=HYPERLINK(\"http://x.example\")", "Milano"))
                .bytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text)
                .startsWith("﻿")
                .isEqualTo("﻿\"Ragione sociale\";\"Città\"\r\n"
                        + "\"Ditta \"\"Uno\"\"; S.r.l.\";\"\"\r\n"
                        + "\"'=HYPERLINK(\"\"http://x.example\"\")\";\"Milano\"\r\n");
        assertThat(CsvFile.cell("Via Roma 1")).isEqualTo("Via Roma 1");
        assertThat(CsvFile.cell("02 1234")).isEqualTo("02 1234");
    }

    @Test
    void thePastedListIsReadAsCodesEachOnce() {
        assertThat(BackofficeToolsController.codes(" TEST-0001\nTEST-0002, test-0001;TEST-0003\t\r\n  "))
                .containsExactly("TEST-0001", "TEST-0002", "TEST-0003");
        assertThatThrownBy(() -> BackofficeToolsController.codes(" \n , "))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("almeno un codice");
        String many = String.join(
                "\n", IntStream.rangeClosed(1, 201).mapToObj(i -> "C-" + i).toList());
        assertThatThrownBy(() -> BackofficeToolsController.codes(many))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("200");
    }
}
