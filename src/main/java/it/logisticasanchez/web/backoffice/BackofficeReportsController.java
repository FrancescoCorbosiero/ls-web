package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.reports.ReportService;
import it.logisticasanchez.web.reports.ReportView;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * «Report» (ADM-09, chapter 04 §4.9 as a page; ADR 0016): the customer, the inclusive date range
 * (Europe/Rome, defaults to the current month), «solo consegnati», «invia anche al cliente», the
 * extra addresses (allow-listed), «Invia Report»; success «Report inviato correttamente» with the
 * row count and the download link; the last runs below. Rules live in {@link ReportService}.
 */
@Controller
public class BackofficeReportsController {

    private final ReportService reports;
    private final CustomerService customers;
    private final Clock clock;

    public BackofficeReportsController(ReportService reports, CustomerService customers, Clock clock) {
        this.reports = reports;
        this.customers = customers;
        this.clock = clock;
    }

    @GetMapping(BackofficeRoutes.REPORTS)
    public String page(Model model) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ItalianFormats.ROME);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("cliente", "");
        values.put("da", today.withDayOfMonth(1).toString());
        values.put("a", today.toString());
        values.put("destinatari", "");
        return form(model, values, false, false, null);
    }

    @PostMapping(BackofficeRoutes.REPORTS)
    public String send(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : new String[] {"cliente", "da", "a", "destinatari"}) {
            values.put(name, ItalianFormats.trim(form.get(name)));
        }
        boolean onlyDelivered = form.containsKey("soloConsegnati");
        boolean sendToCustomer = form.containsKey("inviaAlCliente");
        try {
            long customerId =
                    values.get("cliente").matches("^[0-9]{1,18}$") ? Long.parseLong(values.get("cliente")) : -1;
            ReportView report = reports.request(
                    StaffSession.of(authentication).requireId(),
                    new ReportService.Request(
                            customerId,
                            values.get("da"),
                            values.get("a"),
                            onlyDelivered,
                            sendToCustomer,
                            values.get("destinatari")));
            redirect.addFlashAttribute("flash", "Report inviato correttamente: " + report.rowCount() + " ordini.");
            redirect.addFlashAttribute("reportId", report.id());
        } catch (RuleViolationException e) {
            return form(model, values, onlyDelivered, sendToCustomer, e.detail());
        }
        return "redirect:" + BackofficeRoutes.REPORTS;
    }

    private String form(
            Model model, Map<String, String> values, boolean onlyDelivered, boolean sendToCustomer, String error) {
        model.addAttribute("values", values);
        model.addAttribute("soloConsegnati", onlyDelivered);
        model.addAttribute("inviaAlCliente", sendToCustomer);
        model.addAttribute("error", error);
        model.addAttribute("customers", customers.listAll());
        model.addAttribute("recent", reports.recent());
        return "backoffice/reports";
    }
}
