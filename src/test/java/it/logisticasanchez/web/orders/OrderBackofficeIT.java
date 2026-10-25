package it.logisticasanchez.web.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The order rules the back office drives (chapter 08 §2.4; ADM-04, ADM-06, ADM-07; EML-05, EML-09):
 * approve = code trimmed and unique ignoring case, amount > 0 with the Italian comma, PRESA event,
 * EML-05 with code, detail, amount and tracking link; decline = EML-05 quoting the code; tracking
 * changes as events, none when the state is set again, EML-09 only when it becomes CONSEGNATO;
 * the internal note separate from the customer's note.
 */
@Tag("ADM-04")
@Tag("ADM-06")
@Tag("ADM-07")
@Tag("EML-05")
@Tag("EML-09")
@SpringBootTest
class OrderBackofficeIT extends AbstractPostgresIT {

    @Autowired
    private OrderService orders;

    @Autowired
    private StaffUserRepository staff;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private BusinessProperties business;

    @Autowired
    private JdbcTemplate jdbc;

    private CustomerTestData.Ids ids;
    private long staffId;

    @BeforeEach
    void reset() {
        ids = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("cliente"), "ACTIVE", "Mittente S.r.l.");
        staffId = staff.save(new StaffUser(
                        "op-" + UUID.randomUUID().toString().substring(0, 8), "Operatore", StaffRole.OPERATOR))
                .getId();
        outbox.deleteAll();
    }

    private OrderView pendingOrder() {
        OrderView order = orders.submit(ids.customerId(), ids.customerUserId(), OrderTestSupport.exampleDraft());
        outbox.deleteAll();
        return order;
    }

    private String uniqueCode() {
        return "LS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void approveAssignsTheCodeAndTheAmountStartsTheTrackingAndMailsTheCustomer() {
        OrderView pending = pendingOrder();
        String code = uniqueCode();
        OrderView approved = orders.approve(pending.id(), staffId, "  " + code + " ", "180,50");
        assertThat(approved.status()).isEqualTo(OrderStatus.APPROVED);
        assertThat(approved.code()).isEqualTo(code);
        assertThat(approved.displayCode()).isEqualTo(code);
        assertThat(approved.amount()).isEqualByComparingTo(new BigDecimal("180.50"));
        assertThat(approved.amountVatIncluded()).isFalse(); // Q-05 default
        assertThat(approved.trackingStateId()).isEqualTo((short) 1);
        assertThat(approved.trackingLabel()).isEqualTo("Presa in carico");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(orders.trackingHistory(pending.id())).singleElement().satisfies(e -> {
            assertThat(e.stateId()).isEqualTo((short) 1);
            assertThat(e.source()).isEqualTo("APPROVAL");
        });
        assertThat(jdbc.queryForObject(
                        "select count(*) from tracking_event where order_id = ?", Long.class, pending.id()))
                .isEqualTo(1);
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        EmailOutbox mail = mails.get(0);
        assertThat(mail.getKind()).isEqualTo("EML-05");
        assertThat(mail.getRecipient()).isEqualTo(ids.email());
        assertThat(mail.getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(mail.getSubject()).isEqualTo("Logistica Sanchez - Conferma ordine N. " + code);
        assertThat(mail.getBodyText())
                .contains("Ti confermiamo la presa in carico dell'ordine N. " + code)
                .contains("Grazie per aver scelto Logistica Sanchez!")
                .contains("Importo: 180,50 €")
                .contains("Tracking: Presa in carico")
                .contains("Codice documento trasporto: DDT-2026-0042")
                .contains("Servizio accessorio: Sponda idraulica")
                .contains("https://logisticasanchez.com/tracking/?code=" + code)
                .doesNotContain("Sanchez Logistica");
        assertThat(mail.getBodyHtml()).contains("Mittente S.r.l.").contains("Magazzino Destinatario S.r.l.");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'order.approve' and entity_ref = ?",
                        Long.class,
                        code))
                .isEqualTo(1);
        assertThatThrownBy(() -> orders.approve(pending.id(), staffId, uniqueCode(), "10"))
                .as("APPROVED is final")
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> orders.decline(pending.id(), staffId, null))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void theCodeMustBeUniqueIgnoringCaseAndTheAmountAPositiveEuroAmount() {
        OrderView first = pendingOrder();
        OrderView second = pendingOrder();
        String code = uniqueCode();
        orders.approve(first.id(), staffId, code, "1.234,56");
        assertThat(orders.find(first.id()).orElseThrow().amount()).isEqualByComparingTo("1234.56");
        assertThatThrownBy(() -> orders.approve(second.id(), staffId, " " + code.toLowerCase() + " ", "10"))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("già stata effettuata una registrazione con questo codice");
        for (String amount : List.of("0", "0,00", "-5", "abc", "", "12,345")) {
            assertThatThrownBy(() -> orders.approve(second.id(), staffId, uniqueCode(), amount))
                    .as("amount %s", amount)
                    .isInstanceOf(RuleViolationException.class);
        }
        assertThatThrownBy(() -> orders.approve(second.id(), staffId, "   ", "10"))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> orders.approve(second.id(), staffId, "x".repeat(65), "10"))
                .isInstanceOf(RuleViolationException.class);
        assertThat(orders.find(second.id()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING);
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(orders.approve(second.id(), staffId, uniqueCode(), "99.9").amount())
                .isEqualByComparingTo("99.90");
        assertThat(orders.pendingApproval()).noneMatch(o -> o.id() == second.id());
    }

    @Test
    void declineMailsTheDeclineTextQuotingTheCode() {
        OrderView pending = pendingOrder();
        OrderView declined = orders.decline(pending.id(), staffId, " Merce non trasportabile ");
        assertThat(declined.status()).isEqualTo(OrderStatus.DECLINED);
        assertThat(declined.declineReason()).isEqualTo("Merce non trasportabile");
        assertThat(declined.declinedAt()).isNotNull();
        EmailOutbox mail = outbox.findAll().get(0);
        assertThat(mail.getKind()).isEqualTo("EML-05");
        assertThat(mail.getRecipient()).isEqualTo(ids.email());
        assertThat(mail.getSubject())
                .isEqualTo("Logistica Sanchez - Aggiornamento ordine rifiutato N. " + pending.tempCode());
        assertThat(mail.getBodyText())
                .contains("Richiesta: ordine " + pending.tempCode() + " invalido, rifiutato dall'amministratore.")
                .contains("Motivo: Merce non trasportabile")
                .contains("Grazie per la comprensione!");
        assertThatThrownBy(() -> orders.approve(pending.id(), staffId, uniqueCode(), "10"))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> orders.setTrackingState(pending.id(), staffId, 1))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void trackingChangesAreEventsAndOnlyConsegnatoMailsTheCustomer() {
        OrderView pending = pendingOrder();
        assertThatThrownBy(() -> orders.setTrackingState(pending.id(), staffId, 2))
                .as("approved orders only")
                .isInstanceOf(RuleViolationException.class);
        String code = uniqueCode();
        orders.approve(pending.id(), staffId, code, "180,50");
        outbox.deleteAll();

        assertThat(orders.setTrackingState(pending.id(), staffId, 2).trackingLabel())
                .isEqualTo("Transito");
        assertThat(orders.setTrackingState(pending.id(), staffId, 4).trackingLabel())
                .isEqualTo("Assegnato");
        assertThat(outbox.findAll()).isEmpty();
        assertThatThrownBy(() -> orders.setTrackingState(pending.id(), staffId, 9))
                .isInstanceOf(RuleViolationException.class);

        OrderView delivered = orders.setTrackingState(pending.id(), staffId, 5);
        assertThat(delivered.trackingLabel()).isEqualTo("Consegnato");
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-09");
        assertThat(mails.get(0).getRecipient()).isEqualTo(ids.email());
        assertThat(mails.get(0).getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(mails.get(0).getSubject())
                .isEqualTo("Logistica Sanchez - Consegna effettuata per ordine N. " + code);
        assertThat(mails.get(0).getBodyText())
                .contains("Ti confermiamo l'avvenuta consegna dell'ordine N. " + code)
                .contains("verificare lo stato di qualsiasi spedizione")
                .contains("Importo: 180,50 €")
                .contains("https://logisticasanchez.com/tracking/?code=" + code);

        // setting CONSEGNATO again: no event, no e-mail; going back and forth: events, no e-mail; CONSEGNATO again:
        // e-mail
        orders.setTrackingState(pending.id(), staffId, 5);
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(orders.trackingHistory(pending.id()))
                .extracting(OrderView.TrackingChange::stateId)
                .containsExactly((short) 1, (short) 2, (short) 4, (short) 5);
        orders.setTrackingState(pending.id(), staffId, 0);
        orders.setTrackingState(pending.id(), staffId, 5);
        assertThat(outbox.findAll()).hasSize(2);
        assertThat(orders.trackingHistory(pending.id()))
                .extracting(OrderView.TrackingChange::stateId)
                .containsExactly((short) 1, (short) 2, (short) 4, (short) 5, (short) 0, (short) 5);
        assertThat(orders.trackingHistory(pending.id()))
                .extracting(OrderView.TrackingChange::source)
                .containsExactly("APPROVAL", "STAFF", "STAFF", "STAFF", "STAFF", "STAFF");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'order.tracking'", Long.class))
                .isGreaterThanOrEqualTo(5);
        Map<String, Object> row = jdbc.queryForMap(
                "select tracking_state_id, tracking_updated_at from shipment_order where id = ?", pending.id());
        assertThat(row).containsEntry("tracking_state_id", 5);
        assertThat(row.get("tracking_updated_at")).isNotNull();
    }

    @Test
    void theInternalNoteIsSeparateFromTheCustomersNoteAndAudited() {
        OrderView pending = pendingOrder();
        assertThat(orders.internalNote(pending.id())).isEmpty();
        orders.setInternalNote(pending.id(), staffId, "  Chiamare prima del ritiro ");
        assertThat(orders.internalNote(pending.id())).contains("Chiamare prima del ritiro");
        assertThat(orders.find(pending.id()).orElseThrow().customerNotes()).isEqualTo("Consegna al mattino");
        orders.setInternalNote(pending.id(), staffId, "Ritiro confermato");
        assertThat(orders.internalNote(pending.id())).contains("Ritiro confermato");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log l join audit_log_detail d on d.audit_log_id = l.id"
                                + " where l.action = 'order.note' and l.entity_id = ?",
                        Long.class,
                        pending.id()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'order.note' and (before::text like '%Chiamare%' or after::text like '%Chiamare%')",
                        Long.class))
                .as("the note text lives only in the detail row")
                .isZero();
        orders.setInternalNote(pending.id(), staffId, "");
        assertThat(orders.internalNote(pending.id())).isEmpty();
        assertThatThrownBy(() -> orders.setInternalNote(pending.id(), staffId, "x".repeat(5001)))
                .isInstanceOf(RuleViolationException.class);
    }
}
