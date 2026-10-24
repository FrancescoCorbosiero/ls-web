package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** PUB-02: configuration values become JavaScript string literals that cannot break out of the literal or the script. */
@Tag("PUB-02")
class ConfigJsControllerTest {

    @Test
    void valuesAreQuotedAndEscaped() {
        assertThat(ConfigJsController.js("Logistica Sanchez")).isEqualTo("'Logistica Sanchez'");
        assertThat(ConfigJsController.js("l'ufficio")).isEqualTo("'l\\'ufficio'");
        assertThat(ConfigJsController.js("a\\b")).isEqualTo("'a\\\\b'");
        assertThat(ConfigJsController.js("</script><script>alert(1)</script>"))
                .doesNotContain("</script>")
                .isEqualTo("'\\u003C/script>\\u003Cscript>alert(1)\\u003C/script>'");
        assertThat(ConfigJsController.js("line\nbreak\r")).isEqualTo("'line\\nbreak\\r'");
        assertThat(ConfigJsController.js("")).isEqualTo("''");
    }
}
