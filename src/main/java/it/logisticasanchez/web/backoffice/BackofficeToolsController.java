package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.customers.CustomerView;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The office tools of M2.1 (ADR 0035), each a 404 while its flag is off: the CSV of «Ordini» with
 * the list's filters and of «Registrati» with its search (ENH-08, {@code admin.exports}; every
 * cell as text, every export audited with its row count) and one tracking state on many codes
 * pasted in «Tracking» (ENH-15, {@code orders.bulkTracking}): the list is checked first — the
 * confirmed orders found, the codes that name none — and applied on confirmation, at one time,
 * with the rules and e-mails of a single change.
 */
@Controller
public class BackofficeToolsController {

    /** Rows of one export file: more than the office keeps in a list, a guard against a runaway query. */
    static final int EXPORT_MAX_ROWS = 10_000;

    /** Codes of one bulk change. */
    static final int BULK_MAX_CODES = 200;

    private static final Pattern CODE_SEPARATORS = Pattern.compile("[\\s,;]+");
    private static final int EXPORT_PAGE = 500;

    private final OrderService orders;
    private final CustomerService customers;
    private final TrackingCatalog catalog;
    private final FeatureFlags flags;
    private final AuditService audit;
    private final MessageSource messages;
    private final Clock clock;

    public BackofficeToolsController(
            OrderService orders,
            CustomerService customers,
            TrackingCatalog catalog,
            FeatureFlags flags,
            AuditService audit,
            MessageSource messages,
            Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.catalog = catalog;
        this.flags = flags;
        this.audit = audit;
        this.messages = messages;
        this.clock = clock;
    }

    /** ENH-08: the orders of «Ordini» with the same filters, every page, as a CSV file. */
    @GetMapping(BackofficeRoutes.ORDERS_EXPORT)
    public ResponseEntity<byte[]> exportOrders(
            @RequestParam Map<String, String> params,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        requireFlag(FlagKey.ADMIN_EXPORTS);
        OrderFilters filters = OrderFilters.of(params, flags.isEnabled(FlagKey.ADMIN_DASHBOARD));
        if (filters.error() != null) {
            throw new RuleViolationException(filters.error());
        }
        CsvFile csv = new CsvFile()
                .row(List.of(
                        "ID",
                        "Codice",
                        "Codice provvisorio",
                        "Inviato il",
                        "Confermato il",
                        "Cliente mittente",
                        "Codice Doc. Trasporto",
                        "Numero colli",
                        "Destinatario",
                        "Indirizzo",
                        "Città",
                        "Provincia",
                        "Cap",
                        "Telefono",
                        "Email destinatario",
                        "Peso (kg)",
                        "Volume (m³)",
                        "Importo",
                        "Ser. Accessorio",
                        "Stato",
                        "Tracking",
                        "Tracking aggiornato il"));
        int rows = 0;
        for (int page = 0; rows < EXPORT_MAX_ROWS; page++) {
            Page<OrderView> chunk = orders.search(filters.search(), page, EXPORT_PAGE);
            for (OrderView o : chunk) {
                if (rows++ >= EXPORT_MAX_ROWS) {
                    break;
                }
                csv.row(Arrays.asList(
                        Long.toString(o.id()),
                        o.code(),
                        o.tempCode(),
                        ItalianFormats.formatDateTime(o.submittedAt()),
                        o.approvedAt() == null ? "" : ItalianFormats.formatDateTime(o.approvedAt()),
                        o.sender().name(),
                        o.transportDocumentCode(),
                        o.packageCount() == null ? "" : o.packageCount().toString(),
                        o.recipient().name(),
                        o.recipient().addressLine(),
                        o.recipient().city(),
                        o.recipient().province(),
                        o.recipient().postalCode(),
                        o.recipient().phone(),
                        o.recipient().email(),
                        ItalianFormats.formatNumber(o.totalWeightKg(), 2),
                        ItalianFormats.formatNumber(o.totalVolumeM3(), 2),
                        o.amount() == null ? "" : ItalianFormats.formatNumber(o.amount(), 2),
                        o.accessoryServiceLabel(),
                        message("orders.status." + o.status().name()),
                        o.isApproved() ? o.trackingLabel() : "",
                        o.isApproved() && o.trackingUpdatedAt() != null
                                ? ItalianFormats.formatDateTime(o.trackingUpdatedAt())
                                : ""));
            }
            if (!chunk.hasNext()) {
                break;
            }
        }
        exported(authentication, "orders.export", Math.min(rows, EXPORT_MAX_ROWS));
        return file("ordini", csv);
    }

    /** ENH-08: the customers of «Registrati» with the same search, every page, as a CSV file. */
    @GetMapping(BackofficeRoutes.CUSTOMERS_EXPORT)
    public ResponseEntity<byte[]> exportCustomers(
            @RequestParam(name = "q", required = false) String query,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        requireFlag(FlagKey.ADMIN_EXPORTS);
        CsvFile csv = new CsvFile()
                .row(List.of(
                        "ID",
                        "Ragione sociale",
                        "Email",
                        "Indirizzo",
                        "Provincia",
                        "Città",
                        "Cap",
                        "Telefono",
                        "Partita IVA",
                        "Codice fiscale",
                        "Email confermata",
                        "Stato",
                        "Registrato il"));
        int rows = 0;
        for (int page = 0; rows < EXPORT_MAX_ROWS; page++) {
            Page<CustomerView> chunk = customers.search(query, page, EXPORT_PAGE);
            for (CustomerView c : chunk) {
                if (rows++ >= EXPORT_MAX_ROWS) {
                    break;
                }
                csv.row(Arrays.asList(
                        Long.toString(c.id()),
                        c.companyName(),
                        c.email(),
                        c.addressLine(),
                        c.province(),
                        c.city(),
                        c.postalCode(),
                        c.phone(),
                        c.vatNumber(),
                        c.taxCode(),
                        c.emailVerified() ? "Sì" : "No",
                        message("customers.status." + c.status().name()),
                        ItalianFormats.formatDateTime(c.registeredAt())));
            }
            if (!chunk.hasNext()) {
                break;
            }
        }
        exported(authentication, "customers.export", Math.min(rows, EXPORT_MAX_ROWS));
        return file("clienti", csv);
    }

    /**
     * ENH-15: «Controlla l'elenco» ({@code action=preview}) shows the confirmed orders the pasted
     * codes name, their state now and the codes that name none; «Imposta …» ({@code action=apply})
     * reads the same list again and sets the state on the orders found.
     */
    @PostMapping(BackofficeRoutes.TRACKING_BULK)
    public String bulkTracking(
            @RequestParam(name = "codici", required = false) String codesText,
            @RequestParam(name = "stato", required = false) String stateText,
            @RequestParam(name = "action", required = false) String action,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        requireFlag(FlagKey.ORDERS_BULK_TRACKING);
        String typed = codesText == null ? "" : codesText.strip();
        String stateValue = ItalianFormats.trim(stateText);
        try {
            List<String> codes = codes(typed);
            TrackingCatalog.StateView state = OrderFilters.integer(stateValue)
                    .flatMap(catalog::state)
                    .orElseThrow(() -> new RuleViolationException("Scegli il nuovo stato di tracking."));
            OrderService.BulkSelection selection = orders.approvedByCodes(codes);
            if ("apply".equals(action)) {
                if (selection.orders().isEmpty()) {
                    throw new RuleViolationException("Nessun codice dell'elenco è di una spedizione confermata.");
                }
                OrderService.BulkResult result = orders.setTrackingStates(
                        selection.orders().stream().map(OrderView::id).toList(),
                        StaffSession.of(authentication).requireId(),
                        state.id());
                StringBuilder flash = new StringBuilder("Tracking «" + state.label() + "» impostato su "
                        + result.changed().size() + (result.changed().size() == 1 ? " spedizione." : " spedizioni."));
                if (!result.unchanged().isEmpty()) {
                    flash.append(' ')
                            .append(result.unchanged().size())
                            .append(result.unchanged().size() == 1 ? " era già" : " erano già")
                            .append(" in questo stato.");
                }
                if (!selection.notFound().isEmpty()) {
                    flash.append(" Codici non trovati o non ancora confermati: ")
                            .append(String.join(", ", selection.notFound()))
                            .append('.');
                }
                redirect.addFlashAttribute("flash", flash.toString());
                return "redirect:" + BackofficeRoutes.TRACKING;
            }
            model.addAttribute("bulk", new BulkPreview(selection.orders(), selection.notFound(), state));
            return trackingPage(model, typed, stateValue, null);
        } catch (RuleViolationException e) {
            return trackingPage(model, typed, stateValue, e.detail());
        }
    }

    /** The orders found, the codes that name none and the state to set (the page asks to confirm). */
    public record BulkPreview(List<OrderView> orders, List<String> notFound, TrackingCatalog.StateView state) {

        public long alreadyInState() {
            return orders.stream()
                    .filter(o -> o.trackingStateId() == state.id())
                    .count();
        }

        /** The orders whose state the confirmation changes (the button names them; none, no button). */
        public long toChange() {
            return orders.size() - alreadyInState();
        }
    }

    /** The codes of the pasted list: split on spaces, commas, semicolons; each once (ignoring case). */
    static List<String> codes(String text) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String code : CODE_SEPARATORS.split(text == null ? "" : text.strip())) {
            if (!code.isEmpty()) {
                unique.putIfAbsent(code.toLowerCase(Locale.ROOT), code);
            }
        }
        if (unique.isEmpty()) {
            throw new RuleViolationException("Incolla almeno un codice di spedizione.");
        }
        if (unique.size() > BULK_MAX_CODES) {
            throw new RuleViolationException("Al massimo " + BULK_MAX_CODES + " codici per volta.");
        }
        return new ArrayList<>(unique.values());
    }

    /** «Tracking» with the bulk form (the single-code part as on a first visit). */
    private String trackingPage(Model model, String codes, String state, String error) {
        model.addAttribute("codice", "");
        model.addAttribute("order", null);
        model.addAttribute("notFound", false);
        model.addAttribute("states", catalog.states());
        model.addAttribute("error", null);
        model.addAttribute("bulkEnabled", true);
        model.addAttribute("bulkCodes", codes);
        model.addAttribute("bulkState", state);
        model.addAttribute("bulkError", error);
        if (!model.containsAttribute("bulk")) {
            model.addAttribute("bulk", null);
        }
        return "backoffice/tracking";
    }

    private void exported(Authentication authentication, String action, int rows) {
        audit.record(AuditEntry.of(
                        AuditActorType.STAFF, StaffSession.of(authentication).requireId(), action)
                .change(null, Map.of("rows", rows)));
    }

    private ResponseEntity<byte[]> file(String name, CsvFile csv) {
        String filename = name + "-" + LocalDate.now(clock.withZone(ItalianFormats.ROME)) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(csv.bytes());
    }

    private String message(String key) {
        return messages.getMessage(key, null, key, Locale.ITALIAN);
    }

    private void requireFlag(FlagKey flag) {
        if (!flags.isEnabled(flag)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
    }
}
