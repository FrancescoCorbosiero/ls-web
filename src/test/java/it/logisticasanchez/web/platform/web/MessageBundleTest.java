package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PUB-02: every UI text lives in {@code messages.properties} (the only locale): each
 * {@code #{key}} and {@code #messages.msg('key')} reference in the templates resolves, and no
 * key is empty.
 */
@Tag("PUB-02")
class MessageBundleTest {

    private static final Path BUNDLE = Path.of("src", "main", "resources", "messages.properties");
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates");
    private static final Pattern MESSAGE_REFERENCE =
            Pattern.compile("#\\{([A-Za-z0-9_.]+)[(}]|#messages\\.msg\\('([A-Za-z0-9_.]+)'");

    @Test
    void everyKeyUsedByATemplateExistsInTheItalianBundle() throws IOException {
        Properties bundle = new Properties();
        try (var reader = new InputStreamReader(Files.newInputStream(BUNDLE), StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        assertThat(bundle).isNotEmpty();
        bundle.forEach((key, value) ->
                assertThat(value.toString()).as("value of %s", key).isNotBlank());

        List<String> missing = new ArrayList<>();
        int references = 0;
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path template :
                    files.filter(p -> p.toString().endsWith(".html")).toList()) {
                Matcher matcher = MESSAGE_REFERENCE.matcher(Files.readString(template, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    references++;
                    String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (!bundle.containsKey(key)) {
                        missing.add(template.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertThat(references).as("message references found in the templates").isGreaterThan(50);
        assertThat(missing).as("keys missing from messages.properties").isEmpty();
    }
}
