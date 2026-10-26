package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.orders.OrderContent;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.site.forms.LeadStatus;
import it.logisticasanchez.web.site.forms.LeadView;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved quotes (ENH-20, ADR 0037). Staff prepare one from an open website request whose customer
 * may order: the lines, the amount, the last valid day, the accessory service, the destination and
 * a note; the customer gets EML-11 with an unguessable link (the page and the PDF) and, signed
 * in, turns it into a pending order with the quote's lines and service — the request becomes WON.
 * Staff may withdraw a quote not yet ordered. Every change is audited; the flag
 * {@code quotes.saved} is checked by the screens.
 */
@Service
public class QuoteService {

    /** The longest validity staff may give (a guard, not a business rule). */
    static final int MAX_VALID_DAYS = 365;

    static final int NOTE_MAX = 1000;

    private final QuoteRepository quotes;
    private final LeadService leads;
    private final CustomerService customers;
    private final OrderService orders;
    private final TrackingCatalog catalog;
    private final NotificationService notifications;
    private final QuoteMails mails;
    private final AuditService audit;
    private final Clock clock;

    public QuoteService(
            QuoteRepository quotes,
            LeadService leads,
            CustomerService customers,
            OrderService orders,
            TrackingCatalog catalog,
            NotificationService notifications,
            QuoteMails mails,
            AuditService audit,
            Clock clock) {
        this.quotes = quotes;
        this.leads = leads;
        this.customers = customers;
        this.orders = orders;
        this.catalog = catalog;
        this.notifications = notifications;
        this.mails = mails;
        this.audit = audit;
        this.clock = clock;
    }

    /** What staff typed: the lines (already checked by the order form's rules) and the quote's own fields. */
    public record Draft(
            List<OrderDraft.Line> lines,
            String amount,
            LocalDate validUntil,
            Integer accessoryServiceId,
            String recipientCity,
            String recipientPostalCode,
            String note) {}

    /** The day quotes are judged on: today in Europe/Rome. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ItalianFormats.ROME));
    }

    /**
     * A new quote for the customer linked to an open request, e-mailed to the customer (EML-11); a
     * NEW request becomes CONTACTED. Refused when a rule is not met, with the message to show.
     */
    @Transactional
    public QuoteView create(long leadId, long staffUserId, Draft draft) {
        LeadView lead = leads.find(leadId).orElseThrow(() -> new RuleViolationException("Richiesta non trovata."));
        if (!lead.status().open()) {
            throw new RuleViolationException("La richiesta è già chiusa: il preventivo non si prepara più.");
        }
        CustomerView customer = Optional.ofNullable(lead.customerId())
                .flatMap(customers::find)
                .filter(CustomerView::canOrder)
                .orElseThrow(() -> new RuleViolationException(
                        "Collega prima la richiesta a un cliente attivo: il preventivo è per lui."));
        if (draft.lines().isEmpty()) {
            throw new RuleViolationException("Aggiungi almeno una riga di pallet o colli.");
        }
        BigDecimal amount = ItalianFormats.parseDecimal(draft.amount())
                .filter(a -> a.signum() > 0
                        && a.stripTrailingZeros().scale() <= 2
                        && a.compareTo(BigDecimal.valueOf(10_000_000)) < 0)
                .orElseThrow(
                        () -> new RuleViolationException("Inserisci un importo maggiore di zero, per esempio 180,50."))
                .setScale(2);
        LocalDate today = today();
        if (draft.validUntil() == null
                || draft.validUntil().isBefore(today)
                || draft.validUntil().isAfter(today.plusDays(MAX_VALID_DAYS))) {
            throw new RuleViolationException("Scegli fino a quando vale il preventivo: da oggi a un anno.");
        }
        String note = ItalianFormats.trim(draft.note());
        if (note.length() > NOTE_MAX) {
            throw new RuleViolationException("La nota per il cliente può avere al massimo " + NOTE_MAX + " caratteri.");
        }
        Integer service = draft.accessoryServiceId() == null
                ? null
                : catalog.service(draft.accessoryServiceId())
                        .filter(s -> !s.none())
                        .map(TrackingCatalog.ServiceView::id)
                        .orElse(null);
        String token = QuoteTokens.newToken();
        Instant now = clock.instant();
        List<QuoteView.Line> lines = draft.lines().stream()
                .map(l -> new QuoteView.Line(
                        0, l.kind(), l.heightCm(), l.lengthCm(), l.widthCm(), l.weightKg(), l.quantity()))
                .toList();
        long id = quotes.insert(
                new QuoteRepository.NewQuote(
                        leadId,
                        customer.id(),
                        staffUserId,
                        lines,
                        amount,
                        draft.validUntil(),
                        service,
                        blankToNull(draft.recipientCity()),
                        blankToNull(draft.recipientPostalCode()),
                        note.isEmpty() ? null : note),
                QuoteTokens.hash(token),
                now);
        QuoteView quote = find(id).orElseThrow();
        notifications.enqueue(mails.quote(quote, customer.email(), customer.city(), token));
        if (lead.status() == LeadStatus.NEW) {
            leads.changeStatus(leadId, staffUserId, LeadStatus.CONTACTED);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("leadId", leadId);
        after.put("customerId", customer.id());
        after.put("amount", amount);
        after.put("validUntil", draft.validUntil().toString());
        after.put("lines", lines.size());
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "quote.create")
                .entity("quote", id, quote.code())
                .change(null, after));
        return quote;
    }

    @Transactional(readOnly = true)
    public Optional<QuoteView> find(long id) {
        return quotes.find(id).map(this::labelled);
    }

    /** The quote of a link; nothing for a malformed or unknown token. */
    @Transactional(readOnly = true)
    public Optional<QuoteView> byToken(String token) {
        if (!QuoteTokens.wellFormed(token)) {
            return Optional.empty();
        }
        return quotes.findByTokenHash(QuoteTokens.hash(token)).map(this::labelled);
    }

    @Transactional(readOnly = true)
    public List<QuoteView> forLead(long leadId) {
        return quotes.forLead(leadId).stream().map(this::labelled).toList();
    }

    /** The customer's quotes that can still be ordered. */
    @Transactional(readOnly = true)
    public List<QuoteView> openFor(long customerId) {
        return quotes.openForCustomer(customerId, today()).stream()
                .map(this::labelled)
                .toList();
    }

    /** The quote an order was made from (the staff order page, the approval's amount). */
    @Transactional(readOnly = true)
    public Optional<QuoteView> forOrder(long orderId) {
        return quotes.findByOrder(orderId).map(this::labelled);
    }

    /** Staff take back a quote not yet ordered; its link then says so. */
    @Transactional
    public void withdraw(long id, long staffUserId) {
        QuoteView quote = find(id).orElseThrow(() -> new RuleViolationException("Preventivo non trovato."));
        if (!quotes.withdraw(id, clock.instant())) {
            throw new RuleViolationException("Il preventivo " + quote.code() + " non è più ritirabile.");
        }
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "quote.withdraw")
                .entity("quote", id, quote.code())
                .change(Map.of("status", quote.status().name()), Map.of("status", QuoteStatus.WITHDRAWN.name())));
    }

    /**
     * The customer orders the quote: a pending order (CUS-04 rules and e-mails) with the quote's
     * lines and accessory service and what the customer typed (transport document, packages,
     * notes, recipient); the quote becomes ACCEPTED and the request WON, in one transaction.
     */
    @Transactional
    public OrderView accept(long quoteId, long customerId, long customerUserId, OrderContent typed) {
        QuoteView quote = find(quoteId)
                .filter(q -> q.customerId() == customerId)
                .orElseThrow(() -> new RuleViolationException("Preventivo non trovato."));
        if (!quote.open(today())) {
            throw new RuleViolationException(closedMessage(quote));
        }
        OrderDraft draft = new OrderDraft();
        for (QuoteView.Line line : quote.lines()) {
            draft.addLine(
                    line.kind(), line.heightCm(), line.lengthCm(), line.widthCm(), line.weightKg(), line.quantity());
        }
        draft.setDetails(
                typed.transportDocumentCode(),
                typed.packageCount() == null ? "" : typed.packageCount().toString(),
                quote.accessoryServiceId(),
                typed.notes());
        draft.setRecipient(typed.recipient());
        OrderView order = orders.submit(customerId, customerUserId, draft);
        if (!quotes.accept(quoteId, order.id(), clock.instant())) {
            throw new RuleViolationException(closedMessage(find(quoteId).orElse(quote)));
        }
        leads.converted(quote.leadId(), AuditActorType.CUSTOMER, customerUserId, customerId, order.id());
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "quote.accept")
                .entity("quote", quoteId, quote.code())
                .change(
                        Map.of("status", QuoteStatus.SENT.name()),
                        Map.of("status", QuoteStatus.ACCEPTED.name(), "orderId", order.id())));
        return order;
    }

    /** Export on request (NFR-03): the customer's quotes, each with its reference. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportFor(long customerId) {
        return quotes.exportFor(customerId).stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    copy.put("codice", QuoteView.code(((Number) row.get("id")).longValue()));
                    copy.putAll(row);
                    return copy;
                })
                .toList();
    }

    /** Erasure (NFR-03): the customer's open quotes withdrawn, notes and destinations emptied; returns how many. */
    @Transactional
    public int erase(long customerId) {
        return quotes.erase(customerId, clock.instant());
    }

    /** Why a quote can no longer be ordered, as the customer reads it. */
    public String closedMessage(QuoteView quote) {
        return switch (quote.status()) {
            case ACCEPTED -> "Hai già trasformato il preventivo " + quote.code() + " in un ordine.";
            case WITHDRAWN -> "Il preventivo " + quote.code() + " è stato ritirato: chiedici un nuovo preventivo.";
            case SENT ->
                "Il preventivo " + quote.code() + " è scaduto il " + quote.validUntilText()
                        + ": chiedici di aggiornarlo.";
        };
    }

    private QuoteView labelled(QuoteView q) {
        String label = q.accessoryServiceId() == null
                ? null
                : catalog.service(q.accessoryServiceId())
                        .map(TrackingCatalog.ServiceView::label)
                        .orElse(null);
        return new QuoteView(
                q.id(),
                q.leadId(),
                q.customerId(),
                q.status(),
                q.amount(),
                q.validUntil(),
                q.accessoryServiceId(),
                label,
                q.recipientCity(),
                q.recipientPostalCode(),
                q.note(),
                q.createdBy(),
                q.createdAt(),
                q.orderId(),
                q.acceptedAt(),
                q.withdrawnAt(),
                q.lines());
    }

    private static String blankToNull(String value) {
        String v = ItalianFormats.trim(value);
        return v.isEmpty() ? null : v;
    }
}
