package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.notifications.InMemoryMailSender;
import it.logisticasanchez.web.notifications.OutboxWorker;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * ADM-01: an ADMIN creates the other accounts through the service; the set-password link goes
 * out through the outbox (kind {@code EML-S1}), the raw token only encrypted and wiped after
 * delivery, the audit log records the action; an OPERATOR may not create accounts.
 */
@Tag("ADM-01")
@SpringBootTest
class StaffAccountServiceIT extends AbstractPostgresIT {

    private static final Pattern LINK = Pattern.compile("/gestione/imposta-password\\?t=([A-Za-z0-9_-]+)");

    @Autowired
    private StaffAccountService accounts;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private InMemoryMailSender sender;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void anAdminCreatesAnAccountAndTheLinkIsEmailedThroughTheOutbox() {
        outbox.deleteAll();
        sender.clear();
        String username = "nuovo-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@staff.example";

        StaffUser created = accounts.createByAdmin(1L, username, "Nuovo Operatore", email, StaffRole.OPERATOR);

        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getEmail()).isEqualTo(email);
        EmailOutbox row = outbox.findAll().stream()
                .filter(r -> r.getRecipient().equals(email))
                .findFirst()
                .orElseThrow();
        assertThat(row.getKind()).isEqualTo("EML-S1");
        assertThat(row.getSubject()).contains("Imposta la password");
        assertThat(row.getBodyText())
                .contains("/gestione/imposta-password?t={{token}}")
                .contains("Nuovo Operatore");
        assertThat(row.getTokenCiphertext()).isNotNull();
        assertThat(row.getLoginTokenId()).isNull();

        assertThat(worker.runOnce()).isEqualTo(1);
        Matcher link = LINK.matcher(sender.sent().get(0).bodyText());
        assertThat(link.find()).isTrue();
        assertThat(outbox.findById(row.getId()).orElseThrow().getTokenCiphertext())
                .as("the token is wiped after delivery")
                .isNull();
        assertThat(accounts.peekSetupLink(link.group(1)))
                .as("the e-mailed link opens the set-password page of that account")
                .map(StaffUser::getUsername)
                .contains(username);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'staff.create' and entity_id = ?",
                        Long.class,
                        created.getId()))
                .isEqualTo(1L);
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERATOR")
    void anOperatorMayNotCreateAccounts() {
        String username = "vietato-" + UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(
                        () -> accounts.createByAdmin(2L, username, null, username + "@staff.example", StaffRole.ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }
}
