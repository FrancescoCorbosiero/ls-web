package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.ReportsProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports module's public service (ADM-09, EML-07; ADR 0016): a report = the approved
 * orders of one customer submitted in an inclusive Europe/Rome date range, optionally only the
 * delivered ones. {@link #request} stores the {@code report_run} row, queues EML-07 to the
 * office (always), to the customer (if chosen and allowed by Q-08) and to the typed extra
 * addresses (each must be on the ADMIN allow-list, or the whole request is refused) and writes
 * the audit log (user, range, filter, row count; the addresses in the detail). The PDF is
 * regenerated at every authenticated download ({@link #pdfForStaff}, {@link #pdfForCustomer}).
 */
@Service
public class ReportService {

    private final ReportRunRepository runs;
    private final ReportRecipientService recipients;
    private final CustomerService customers;
    private final OrderService orders;
    private final ReportPdf pdf;
    private final ReportMails mails;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ReportsProperties settings;
    private final BusinessProperties business;
    private final Clock clock;

    public ReportService(
            ReportRunRepository runs,
            ReportRecipientService recipients,
            CustomerService customers,
            OrderService orders,
            ReportPdf pdf,
            ReportMails mails,
            NotificationService notifications,
            AuditService audit,
            ReportsProperties settings,
            BusinessProperties business,
            Clock clock) {
        this.runs = runs;
        this.recipients = recipients;
        this.customers = customers;
        this.orders = orders;
        this.pdf = pdf;
        this.mails = mails;
        this.notifications = notifications;
        this.audit = audit;
        this.settings = settings;
        this.business = business;
        this.clock = clock;
    }

    /** What the form sends: the customer, the range as typed, the two flags and the extra addresses (free text). */
    public record Request(
            long customerId,
            String fromText,
            String toText,
            boolean onlyDelivered,
            boolean sendToCustomer,
            String extraRecipientsText) {

        public Request withCustomer(long id) {
            return new Request(id, fromText, toText, onlyDelivered, sendToCustomer, extraRecipientsText);
        }
    }

    @Transactional
    public ReportView request(long staffUserId, Request request) {
        Instant now = clock.instant();
        CustomerView customer = customers
                .find(request.customerId())
                .filter(c -> !c.anonymised())
                .orElseThrow(() -> new RuleViolationException("Seleziona un cliente."));
        ReportPeriod period = ReportPeriod.parse(request.fromText(), request.toText(), now);
        if (request.sendToCustomer() && !settings.customerCanReceive()) { // Q-08
            throw new RuleViolationException("L'invio del report al cliente non è abilitato.");
        }
        Set<String> addresses = new LinkedHashSet<>();
        addresses.add(ReportRecipientService.normalise(business.officeEmail()));
        if (request.sendToCustomer()) {
            addresses.add(ReportRecipientService.normalise(customer.email()));
        }
        List<String> extra = parseAddresses(request.extraRecipientsText());
        for (String address : extra) {
            if (!ItalianFormats.isEmail(address)) {
                throw new RuleViolationException("L'indirizzo «" + address + "» non è valido.");
            }
            if (!address.equals(ReportRecipientService.normalise(customer.email())) && !recipients.allows(address)) {
                throw new RuleViolationException(
                        "L'indirizzo «" + address
                                + "» non è tra i destinatari autorizzati: un amministratore può aggiungerlo nelle impostazioni.");
            }
            addresses.add(address);
        }
        List<OrderView> rows =
                orders.approvedForReport(customer.id(), period.from(), period.to(), request.onlyDelivered());
        ReportRun run = runs.save(new ReportRun(
                customer.id(),
                staffUserId,
                period.from(),
                period.to(),
                request.onlyDelivered(),
                request.sendToCustomer(),
                List.copyOf(addresses),
                rows.size(),
                now));
        ReportView view = ReportView.of(run);
        String customerAddress = ReportRecipientService.normalise(customer.email());
        for (String address : addresses) {
            if (address.equals(customerAddress)) {
                notifications.enqueue(mails.toCustomer(view, customer));
            } else {
                notifications.enqueue(mails.toOffice(address, view, customer));
            }
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("customerId", customer.id());
        change.put("from", period.from().toString());
        change.put("to", period.to().toString());
        change.put("onlyDelivered", request.onlyDelivered());
        change.put("sendToCustomer", request.sendToCustomer());
        change.put("rowCount", rows.size());
        change.put("recipientCount", addresses.size());
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "report.send")
                .entity("report_run", run.getId(), null)
                .change(null, change)
                .personal(null, Map.of("recipients", String.join(", ", addresses))));
        return view;
    }

    /** The PDF of a run for a staff session; 404 for an unknown id. */
    @Transactional(readOnly = true)
    public Download pdfForStaff(long runId) {
        ReportRun run = runs.findById(runId).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        return render(run);
    }

    /** The PDF of a run for the customer it is about; 403 for another customer's run, 404 for an unknown id. */
    @Transactional(readOnly = true)
    public Download pdfForCustomer(long runId, long customerId) {
        ReportRun run = runs.findById(runId).orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        if (run.getCustomerId() != customerId) {
            throw new ClientErrorException(ErrorCode.FORBIDDEN);
        }
        return render(run);
    }

    @Transactional(readOnly = true)
    public Optional<ReportView> find(long runId) {
        return runs.findById(runId).map(ReportView::of);
    }

    /** The last runs, newest first (the report page). */
    @Transactional(readOnly = true)
    public List<ReportView> recent() {
        return runs.findTop20ByOrderByIdDesc().stream().map(ReportView::of).toList();
    }

    /** Erasure (NFR-03): the erased address replaced in every run's recipient list. */
    @Transactional
    public int eraseRecipient(String email, String placeholder) {
        int touched = 0;
        for (ReportRun run : runs.findAll()) {
            if (run.getRecipients().stream().anyMatch(r -> r.equalsIgnoreCase(email))) {
                run.replaceRecipient(email, placeholder);
                touched++;
            }
        }
        return touched;
    }

    private Download render(ReportRun run) {
        CustomerView customer = customers.find(run.getCustomerId()).orElseThrow();
        ReportView view = ReportView.of(run);
        List<OrderView> rows =
                orders.approvedForReport(customer.id(), run.getDateFrom(), run.getDateTo(), run.isOnlyDelivered());
        return new Download(view.fileName(), pdf.render(customer, view, rows));
    }

    /** The rendered PDF with its file name. */
    public record Download(String fileName, byte[] bytes) {}

    /** Splits the typed addresses on commas, semicolons and whitespace; normalised, de-duplicated. */
    static List<String> parseAddresses(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String piece : text.split("[,;\\s]+")) {
            String address = ReportRecipientService.normalise(piece);
            if (!address.isEmpty() && !result.contains(address)) {
                result.add(address);
            }
        }
        return result;
    }
}
