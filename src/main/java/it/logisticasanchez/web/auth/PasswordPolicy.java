package it.logisticasanchez.web.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The staff password policy (ADM-01, chapter 10 §4): at least {@value #MIN_LENGTH} characters
 * and not in the bundled list of common passwords ({@code auth/common-passwords.txt}, compared
 * case-insensitively after trimming). Nothing else is required: length and the deny-list are
 * what the brief asks, and composition rules push people to predictable passwords.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    static final String LIST = "auth/common-passwords.txt";

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = load();
    }

    /** The reason a password is refused, or empty when it is acceptable. */
    public Optional<Violation> check(String password) {
        if (password == null || password.strip().length() < MIN_LENGTH) {
            return Optional.of(Violation.TOO_SHORT);
        }
        if (common.contains(password.strip().toLowerCase(Locale.ROOT))) {
            return Optional.of(Violation.COMMON);
        }
        return Optional.empty();
    }

    public boolean isAcceptable(String password) {
        return check(password).isEmpty();
    }

    int commonPasswords() {
        return common.size();
    }

    private static Set<String> load() {
        InputStream stream = PasswordPolicy.class.getClassLoader().getResourceAsStream(LIST);
        if (stream == null) {
            throw new IllegalStateException("common-password list " + LIST + " is missing from the classpath");
        }
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (words.size() < 1000) {
            throw new IllegalStateException("common-password list is too short: " + words.size());
        }
        return Set.copyOf(words);
    }

    /** Why a password is refused; the Italian message is in the bundle ({@code auth.password.*}). */
    public enum Violation {
        TOO_SHORT("auth.password.tooShort"),
        COMMON("auth.password.common");

        private final String messageKey;

        Violation(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }
}
