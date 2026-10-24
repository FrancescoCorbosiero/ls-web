package it.logisticasanchez.web.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.auth.CustomerSignInService;
import it.logisticasanchez.web.auth.LoginTokenPurpose;
import it.logisticasanchez.web.auth.LoginTokenService;
import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The customer state machine of chapter 08 §2.4 as the back office drives it (CUS-02, ADM-02,
 * EML-03): approve only a verified customer waiting for approval (Q-28) → ACTIVE + EML-03;
 * decline from either pending state → DECLINED + EML-03 with the decline text and reason;
 * illegal moves refused; the sign-in link verifies a PENDING_VERIFICATION customer (EML-02 +
 * EML-06 once); every transition audited.
 */
@Tag("CUS-02")
@Tag("ADM-02")
@Tag("EML-03")
@SpringBootTest
class CustomerServiceIT extends AbstractPostgresIT {

    @Autowired
    private CustomerService customers;

    @Autowired
    private CustomerSignInService signIn;

    @Autowired
    private LoginTokenService tokens;

    @Autowired
    private StaffUserRepository staff;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private BusinessProperties business;

    @Autowired
    private JdbcTemplate jdbc;

    private long staffId;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        staffId = staff.save(new StaffUser(
                        "op-" + UUID.randomUUID().toString().substring(0, 8), "Operatore Test", StaffRole.OPERATOR))
                .getId();
    }

    @Test
    void approveMovesAVerifiedPendingCustomerToActiveAndMailsIt() {
        CustomerTestData.Ids ids =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("attesa"), "PENDING_APPROVAL");
        CustomerView view = customers.approve(ids.customerId(), staffId);
        assertThat(view.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(view.approvedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select approved_by from customer where id = ?", Long.class, ids.customerId()))
                .isEqualTo(staffId);
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-03");
        assertThat(mails.get(0).getRecipient()).isEqualTo(ids.email());
        assertThat(mails.get(0).getSubject()).isEqualTo("Logistica Sanchez - Conferma nuovo cliente");
        assertThat(mails.get(0).getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(mails.get(0).getBodyText())
                .contains("Benvenuto in Logistica Sanchez!")
                .contains("Da questo momento in poi fai parte dei clienti del nostro centro logistico.")
                .contains("https://logisticasanchez.com/area-clienti/")
                .doesNotContain("Sanchez Logistica");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'customer.approve' and actor_id = ? and entity_id = ?",
                        Long.class,
                        staffId,
                        ids.customerId()))
                .isEqualTo(1);
        assertThatThrownBy(() -> customers.approve(ids.customerId(), staffId))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void anUnverifiedCustomerCannotBeApproved() {
        CustomerTestData.Ids ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("nonverificato"), "PENDING_VERIFICATION");
        assertThatThrownBy(() -> customers.approve(ids.customerId(), staffId))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("attesa di approvazione");
        assertThat(customers.find(ids.customerId()).orElseThrow().status())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION);
        assertThat(outbox.findAll()).isEmpty();
        CustomerTestData.Ids declined =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("rifiutato"), "DECLINED");
        assertThatThrownBy(() -> customers.approve(declined.customerId(), staffId))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void declineFromEitherPendingStateMailsTheDeclineTextWithTheReason() {
        CustomerTestData.Ids ids =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("attesa"), "PENDING_APPROVAL");
        CustomerView view = customers.decline(ids.customerId(), staffId, "  Dati incompleti ");
        assertThat(view.status()).isEqualTo(CustomerStatus.DECLINED);
        assertThat(view.declineReason()).isEqualTo("Dati incompleti");
        EmailOutbox mail = outbox.findAll().get(0);
        assertThat(mail.getKind()).isEqualTo("EML-03");
        assertThat(mail.getRecipient()).isEqualTo(ids.email());
        assertThat(mail.getSubject()).isEqualTo("Logistica Sanchez - Registrazione non accettata");
        assertThat(mail.getBodyText())
                .contains("Abbiamo riscontrato un errore nella tua richiesta")
                .contains("rifiutati dall'amministratore")
                .contains("Motivo: Dati incompleti")
                .contains("Grazie per la comprensione!");
        CustomerTestData.Ids unverified = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("nonverificato"), "PENDING_VERIFICATION");
        assertThat(customers.decline(unverified.customerId(), staffId, null).status())
                .isEqualTo(CustomerStatus.DECLINED);
        CustomerTestData.Ids active =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("attivo"), "ACTIVE");
        assertThatThrownBy(() -> customers.decline(active.customerId(), staffId, null))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> customers.decline(ids.customerId(), staffId, null))
                .as("DECLINED is not pending")
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void theSignInLinkVerifiesAPendingVerificationCustomerOnce() {
        CustomerTestData.Ids ids = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("daverificare"), "PENDING_VERIFICATION");
        LoginTokenService.IssuedToken issued =
                tokens.issue(LoginTokenPurpose.SIGN_IN, ids.customerUserId(), "203.0.113.7");
        signIn.signIn(issued.rawToken());
        CustomerView view = customers.find(ids.customerId()).orElseThrow();
        assertThat(view.status()).isEqualTo(CustomerStatus.PENDING_APPROVAL);
        assertThat(view.emailVerifiedAt()).isNotNull();
        assertThat(outbox.findAll()).extracting(EmailOutbox::getKind).containsExactlyInAnyOrder("EML-02", "EML-06");
        // a second sign-in changes nothing and sends nothing
        LoginTokenService.IssuedToken again =
                tokens.issue(LoginTokenPurpose.SIGN_IN, ids.customerUserId(), "203.0.113.7");
        signIn.signIn(again.rawToken());
        assertThat(outbox.findAll()).hasSize(2);
        assertThat(customers.find(ids.customerId()).orElseThrow().status()).isEqualTo(CustomerStatus.PENDING_APPROVAL);
        // and the office can now approve
        assertThat(customers.approve(ids.customerId(), staffId).status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(customers.pendingApproval()).noneMatch(c -> c.id() == ids.customerId());
    }
}
