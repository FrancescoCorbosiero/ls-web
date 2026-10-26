package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ENH-09 (ADR 0039): the codes are those of RFC 6238 (its SHA-1 test vectors, 6 digits), the
 * secret is shown in RFC 4648 Base32, a code is accepted one step around now and never for a step
 * already used, and the QR code carries the {@code otpauth://} URI.
 */
@Tag("ENH-09")
class TotpTest {

    /** The SHA-1 secret of the RFC 6238 test vectors. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theCodesAreThoseOfRfc6238() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109L))))
                .isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111111L))))
                .isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890L))))
                .isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000L))))
                .isEqualTo("279037");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(20000000000L))))
                .isEqualTo("353130");
    }

    @Test
    void theSecretIsTwentyRandomBytesShownInBase32() {
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.base32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        byte[] secret = Totp.newSecret();
        assertThat(secret).hasSize(20);
        assertThat(Totp.newSecret()).isNotEqualTo(secret);
        assertThat(Totp.base32(secret)).hasSize(32).matches("^[A-Z2-7]+$");
    }

    @Test
    void aCodeIsAcceptedOneStepAroundNowAndOnlyOnce() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = Totp.step(now);
        assertThat(Totp.match(RFC_SECRET, "005924", now, null)).hasValue(step);
        assertThat(Totp.match(RFC_SECRET, " 005 924 ", now, null))
                .as("spaces ignored")
                .hasValue(step);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, null))
                .as("the phone a little behind")
                .hasValue(step - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, null))
                .as("the phone a little ahead")
                .hasValue(step + 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null))
                .isEmpty();
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, null))
                .isEmpty();
        assertThat(Totp.match(RFC_SECRET, "005924", now, step))
                .as("a step already used")
                .isEmpty();
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, step))
                .hasValue(step + 1);
        assertThat(Totp.match(RFC_SECRET, "", now, null)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, null, now, null)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "05924", now, null)).isEmpty();
        assertThat(Totp.match(RFC_SECRET, "abcdef", now, null)).isEmpty();
    }

    @Test
    void theQrCodeCarriesTheOtpauthUri() {
        String uri = Totp.uri("Logistica Sanchez", "mario.rossi", RFC_SECRET);
        assertThat(uri)
                // the key is the Base32 checked above, built here so no key-like literal sits in the sources
                .isEqualTo("otpauth://totp/Logistica%20Sanchez:mario.rossi?secret=" + Totp.base32(RFC_SECRET)
                        + "&issuer=Logistica%20Sanchez&algorithm=SHA1&digits=6&period=30");
        TotpQr.Svg qr = TotpQr.of(uri);
        assertThat(qr.size()).isGreaterThanOrEqualTo(21 + 4);
        assertThat(qr.path()).startsWith("M");
        assertThat(qr.path().replaceAll("M[0-9]+ [0-9]+h1v1h-1z", ""))
                .as("one square per dark module")
                .isEmpty();
    }
}
