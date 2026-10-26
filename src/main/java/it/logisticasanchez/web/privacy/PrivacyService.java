package it.logisticasanchez.web.privacy;

import it.logisticasanchez.web.auth.CustomerAccountService;
import it.logisticasanchez.web.customers.AddressBookService;
import it.logisticasanchez.web.customers.AddressView;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.reports.ReportService;
import it.logisticasanchez.web.site.forms.LeadService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The GDPR tools of NFR-03 (chapter 10 §5, ADR 0024), ADMIN only: {@link #export} gathers what the
 * app holds about an e-mail (customer, login identity, orders, requests from the site, reports)
 * as JSON; {@link #anonymise} erases by anonymisation, never by deletion: the customer row and
 * its login identity get a placeholder address and are closed, one-time links are deleted,
 * leads, staff notes, outbox bodies and audit details are removed, report recipient lists are
 * rewritten, the address book of saved recipients (ENH-07) is deleted; the orders stay with
 * their sender snapshots for the legal retention period (Q-18). Each module does its own part
 * through its service; this class only orchestrates.
 */
@Service
public class PrivacyService {

    public static final String PLACEHOLDER_DOMAIN = "anonimizzato.invalid";

    private final CustomerService customers;
    private final CustomerAccountService accounts;
    private final OrderService orders;
    private final LeadService leads;
    private final ReportService reports;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectMapper json;
    private final AddressBookService addressBook;

    public PrivacyService(
            CustomerService customers,
            CustomerAccountService accounts,
            OrderService orders,
            LeadService leads,
            ReportService reports,
            NotificationService notifications,
            AuditService audit,
            ObjectMapper json,
            AddressBookService addressBook) {
        this.customers = customers;
        this.accounts = accounts;
        this.orders = orders;
        this.leads = leads;
        this.reports = reports;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
        this.addressBook = addressBook;
    }

    /** What the tool found for an address before acting: the customer, if any, and the number of site requests. */
    public record Subject(String email, Optional<CustomerView> customer, int leadCount) {
        public boolean isEmpty() {
            return customer.isEmpty() && leadCount == 0;
        }
    }

    @Transactional(readOnly = true)
    public Subject lookup(String email) {
        String normalised = normalise(email);
        if (normalised.isEmpty() || !normalised.contains("@")) {
            throw new RuleViolationException("Inserisci un indirizzo e-mail.");
        }
        return new Subject(
                normalised,
                customers.findByEmail(normalised).filter(c -> !c.anonymised()),
                leads.exportByEmail(normalised).size());
    }

    /** The data held about the address, as a JSON document (UTF-8 text); audited with the customer id only. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String export(long adminId, String email) {
        Subject subject = lookup(email);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("email", subject.email());
        subject.customer().ifPresent(customer -> {
            document.put("cliente", customer);
            accounts.findByEmail(subject.email())
                    .ifPresent(account -> document.put(
                            "accesso",
                            Map.of(
                                    "email", account.email(),
                                    "disabilitato", account.disabled(),
                                    "emailInAttesa", account.pendingEmail() == null ? "" : account.pendingEmail())));
            List<Map<String, Object>> orderRows = new ArrayList<>();
            for (OrderView order : orders.allForCustomer(customer.id())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("codice", order.displayCode());
                row.put("stato", order.status().name());
                row.put("inviatoIl", order.submittedAt());
                row.put("approvatoIl", order.approvedAt());
                row.put("tracking", order.trackingLabel());
                row.put("documentoTrasporto", order.transportDocumentCode());
                row.put("servizioAccessorio", order.accessoryServiceLabel());
                row.put("note", order.customerNotes());
                row.put("importo", order.amount());
                row.put("pesoKg", order.totalWeightKg());
                row.put("volumeM3", order.totalVolumeM3());
                row.put("mittente", order.sender());
                row.put("destinatario", order.recipient());
                row.put("righe", order.lines());
                orderRows.add(row);
            }
            document.put("ordini", orderRows);
            // ENH-07: only when the customer kept an address book (the M1 document is unchanged)
            List<AddressView> book = addressBook.list(customer.id());
            if (!book.isEmpty()) {
                document.put(
                        "rubricaDestinatari",
                        book.stream().map(AddressView::fields).toList());
            }
        });
        document.put(
                "richiesteDalSito",
                leads.exportFor(
                        subject.email(),
                        subject.customer().map(CustomerView::id).orElse(null)));
        audit.record(AuditEntry.of(AuditActorType.STAFF, adminId, "privacy.export")
                .entity("customer", subject.customer().map(CustomerView::id).orElse(null), null)
                .change(
                        null,
                        Map.of(
                                "leads",
                                subject.leadCount(),
                                "customer",
                                subject.customer().isPresent())));
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(document);
    }

    /** What the anonymisation did, by counts (never values); {@code addresses}: the saved recipients deleted (ENH-07). */
    public record Outcome(
            Long customerId, int leads, int outboxRows, int notes, int reportRuns, int auditDetails, int addresses) {}

    /**
     * Erasure by anonymisation (NFR-03). Refused when nothing is known about the address. The
     * placeholder is {@code cliente-<id>@anonimizzato.invalid} for a customer, otherwise the
     * leads' own placeholder.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Outcome anonymise(long adminId, String email) {
        Subject subject = lookup(email);
        if (subject.isEmpty()) {
            throw new RuleViolationException("Nessun dato trovato per questo indirizzo.");
        }
        String address = subject.email();
        Long customerId = subject.customer().map(CustomerView::id).orElse(null);
        String placeholder = customerId == null
                ? "richiesta@" + PLACEHOLDER_DOMAIN
                : "cliente-" + customerId + "@" + PLACEHOLDER_DOMAIN;
        int outboxRows = notifications.eraseRecipient(address, placeholder);
        int reportRuns = reports.eraseRecipient(address, placeholder);
        int notes = 0;
        int auditDetails = 0;
        int addresses = 0;
        if (customerId != null) {
            Optional<CustomerAccountService.Account> account = accounts.findByEmail(address);
            List<String> otherAddresses = accounts.anonymise(customerId, placeholder);
            for (String other : otherAddresses) {
                if (!other.equalsIgnoreCase(address)) {
                    outboxRows += notifications.eraseRecipient(other, placeholder);
                }
            }
            notes = orders.eraseInternalNotes(customerId);
            addresses = addressBook.erase(customerId);
            auditDetails += audit.deletePersonalDetails(
                    "customer",
                    customerId,
                    AuditActorType.CUSTOMER,
                    account.map(CustomerAccountService.Account::customerUserId).orElse(null));
            for (OrderView order : orders.allForCustomer(customerId)) {
                auditDetails +=
                        audit.deletePersonalDetails("shipment_order", order.id(), AuditActorType.CUSTOMER, null);
            }
            customers.anonymise(customerId, adminId, placeholder);
        }
        int leadRows = leads.anonymiseByEmail(address);
        if (customerId != null) {
            leadRows += leads.anonymiseByCustomer(customerId); // ENH-06: linked with another e-mail
        }
        auditDetails += audit.deletePersonalDetailsMentioning(address);
        Outcome outcome = new Outcome(customerId, leadRows, outboxRows, notes, reportRuns, auditDetails, addresses);
        audit.record(AuditEntry.of(AuditActorType.STAFF, adminId, "privacy.anonymise")
                .entity("customer", customerId, null)
                .change(null, counts(outcome)));
        return outcome;
    }

    /** The anonymisation's counts for the audit log; the address book only when there was one (ENH-07). */
    private static Map<String, Object> counts(Outcome outcome) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("leads", outcome.leads());
        counts.put("outboxRows", outcome.outboxRows());
        counts.put("notes", outcome.notes());
        counts.put("reportRuns", outcome.reportRuns());
        counts.put("auditDetails", outcome.auditDetails());
        if (outcome.addresses() > 0) {
            counts.put("addresses", outcome.addresses());
        }
        return counts;
    }

    private static String normalise(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
