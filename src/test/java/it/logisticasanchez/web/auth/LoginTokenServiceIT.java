package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CUS-01 (chapter 10 §2 "Tokens", chapter 08 §2.4): the token service for every purpose — the
 * {@code VERIFY_EMAIL} and {@code CHANGE_EMAIL} links the Phase 2 {@code customers} module
 * consumes follow the same rule as sign-in: random, stored hashed, peek never consumes, consume
 * works once, a token of another purpose or past its lifetime (15 minutes) is refused.
 */
@Tag("CUS-01")
@SpringBootTest
@Import(MutableClock.Configuration.class)
class LoginTokenServiceIT extends AbstractPostgresIT {

    @Autowired
    private LoginTokenService service;

    @Autowired
    private LoginTokenRepository tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private long customerUserId;

    @BeforeEach
    void customer() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        customerUserId = AuthTestData.customerUser(jdbc, AuthTestData.uniqueEmail("token"), "ACTIVE");
    }

    @Test
    void verifyEmailAndChangeEmailTokensAreIssuedHashedPeekedAndConsumedOnce() {
        for (LoginTokenPurpose purpose :
                new LoginTokenPurpose[] {LoginTokenPurpose.VERIFY_EMAIL, LoginTokenPurpose.CHANGE_EMAIL}) {
            LoginTokenService.IssuedToken issued = service.issue(purpose, customerUserId, "203.0.113.1");
            LoginToken stored = tokens.findById(issued.token().getId()).orElseThrow();
            assertThat(issued.rawToken()).hasSizeGreaterThanOrEqualTo(43); // 256 bits in base64url
            assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(issued.rawToken());
            assertThat(stored.getPurpose()).isEqualTo(purpose);
            assertThat(stored.getCustomerUserId()).isEqualTo(customerUserId);
            assertThat(stored.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
            assertThat(stored.getRequestedIpHash()).hasSize(64).doesNotContain("203.0.113.1");

            assertThat(service.peek(issued.rawToken(), purpose)).isPresent();
            assertThat(service.peek(issued.rawToken(), purpose))
                    .as("peek never consumes")
                    .isPresent();
            assertThat(service.peek(issued.rawToken(), LoginTokenPurpose.SIGN_IN))
                    .as("another purpose does not see it")
                    .isEmpty();
            assertThatThrownBy(() -> service.consume(issued.rawToken(), LoginTokenPurpose.SIGN_IN))
                    .isInstanceOf(LoginTokenService.InvalidTokenException.class);

            LoginToken consumed = service.consume(issued.rawToken(), purpose);
            assertThat(consumed.getUsedAt()).isEqualTo(clock.instant());
            assertThat(service.peek(issued.rawToken(), purpose)).isEmpty();
            assertThatThrownBy(() -> service.consume(issued.rawToken(), purpose))
                    .as("a second consume is refused")
                    .isInstanceOf(LoginTokenService.InvalidTokenException.class);
        }
    }

    @Test
    void aTokenPastItsLifetimeIsRefused() {
        LoginTokenService.IssuedToken issued = service.issue(LoginTokenPurpose.VERIFY_EMAIL, customerUserId, null);
        clock.advance(Duration.ofMinutes(14));
        assertThat(service.peek(issued.rawToken(), LoginTokenPurpose.VERIFY_EMAIL))
                .isPresent();
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.peek(issued.rawToken(), LoginTokenPurpose.VERIFY_EMAIL))
                .isEmpty();
        assertThatThrownBy(() -> service.consume(issued.rawToken(), LoginTokenPurpose.VERIFY_EMAIL))
                .isInstanceOf(LoginTokenService.InvalidTokenException.class);
        assertThat(service.peek(null, LoginTokenPurpose.VERIFY_EMAIL)).isEmpty();
        assertThat(service.peek("not-a-token", LoginTokenPurpose.VERIFY_EMAIL)).isEmpty();
    }
}
