package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.tracking.AccessoryServiceAdmin;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * «Servizi accessori» (ENH-14, flag {@code admin.enumerations}; ADR 0043), ADMIN only, under
 * «Impostazioni»: one list for the order, the «Servizi accessori» of {@code /servizi/} and the
 * extras of the quote wizard, each row saying where it shows, with «Su», «Giù», «Modifica»,
 * «Disattiva» and, for the services added after the legacy ones, «Elimina»; the form of a new
 * service. Item 0 is listed and never changed. 404 while the flag is off.
 */
@Controller
public class BackofficeAccessoryServicesController {

    private final AccessoryServiceAdmin services;
    private final FeatureFlags flags;

    public BackofficeAccessoryServicesController(AccessoryServiceAdmin services, FeatureFlags flags) {
        this.services = services;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.ACCESSORY_SERVICES)
    public String list(Model model) {
        requireFlag();
        return page(model, null, values(Map.of()), Map.of());
    }

    @PostMapping(BackofficeRoutes.ACCESSORY_SERVICES)
    public String create(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        requireFlag();
        Map<String, String> errors =
                services.create(StaffSession.of(authentication).requireId(), form);
        if (!errors.isEmpty()) {
            return page(model, null, values(form), errors);
        }
        redirect.addFlashAttribute(
                "flash", "Servizio «" + ItalianFormats.trim(form.get("nome")) + "» aggiunto in fondo all'elenco.");
        return "redirect:" + BackofficeRoutes.ACCESSORY_SERVICES;
    }

    @GetMapping(BackofficeRoutes.ACCESSORY_SERVICE)
    public String edit(@PathVariable("id") String id, Model model) {
        requireFlag();
        AccessoryServiceAdmin.Row row = editable(id);
        return page(model, row, values(row), Map.of());
    }

    @PostMapping(BackofficeRoutes.ACCESSORY_SERVICE)
    public String update(
            @PathVariable("id") String id,
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        requireFlag();
        AccessoryServiceAdmin.Row row = editable(id);
        Map<String, String> errors =
                services.update(StaffSession.of(authentication).requireId(), row, form);
        if (!errors.isEmpty()) {
            Map<String, String> typed = values(form);
            if (row.legacy()) {
                typed.put("ordini", "si"); // its checkbox is disabled: the browser does not send it
            }
            return page(model, row, typed, errors);
        }
        redirect.addFlashAttribute("flash", "Servizio «" + ItalianFormats.trim(form.get("nome")) + "» aggiornato.");
        return "redirect:" + BackofficeRoutes.ACCESSORY_SERVICES;
    }

    @PostMapping(BackofficeRoutes.ACCESSORY_SERVICE_MOVE)
    public String move(
            @PathVariable("id") String id,
            @RequestParam(name = "verso", defaultValue = "") String direction,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        AccessoryServiceAdmin.Row row = editable(id);
        if (!direction.equals("su") && !direction.equals("giu")) {
            throw new ClientErrorException(ErrorCode.BAD_REQUEST);
        }
        if (services.move(StaffSession.of(authentication).requireId(), row, direction.equals("su"))) {
            redirect.addFlashAttribute("flash", "«" + row.label() + "» spostato: l'ordine vale ovunque compare.");
        }
        return "redirect:" + BackofficeRoutes.ACCESSORY_SERVICES;
    }

    @PostMapping(BackofficeRoutes.ACCESSORY_SERVICE_STATE)
    public String state(
            @PathVariable("id") String id,
            @RequestParam(name = "attivo", defaultValue = "") String active,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        AccessoryServiceAdmin.Row row = editable(id);
        if (!active.equals("si") && !active.equals("no")) {
            throw new ClientErrorException(ErrorCode.BAD_REQUEST);
        }
        boolean on = active.equals("si");
        services.setActive(StaffSession.of(authentication).requireId(), row, on);
        redirect.addFlashAttribute(
                "flash",
                on
                        ? "«" + row.label() + "» è di nuovo attivo."
                        : "«" + row.label() + "» disattivato: non compare più, gli ordini che lo hanno lo conservano.");
        return "redirect:" + BackofficeRoutes.ACCESSORY_SERVICES;
    }

    @PostMapping(BackofficeRoutes.ACCESSORY_SERVICE_DELETE)
    public String delete(
            @PathVariable("id") String id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        AccessoryServiceAdmin.Row row = editable(id);
        if (!row.deletable()) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        try {
            services.delete(StaffSession.of(authentication).requireId(), row);
        } catch (DataIntegrityViolationException e) {
            redirect.addFlashAttribute(
                    "flashError",
                    "«" + row.label()
                            + "» è in ordini o preventivi già registrati: non si può eliminare, puoi disattivarlo.");
            return "redirect:" + BackofficeRoutes.ACCESSORY_SERVICES;
        }
        redirect.addFlashAttribute("flash", "«" + row.label() + "» eliminato.");
        return "redirect:" + BackofficeRoutes.ACCESSORY_SERVICES;
    }

    /** The list with the form of a new service ({@code editing} null), or the form of one service. */
    private String page(
            Model model, AccessoryServiceAdmin.Row editing, Map<String, String> values, Map<String, String> errors) {
        model.addAttribute("editing", editing);
        if (editing == null) {
            model.addAttribute("services", services.all());
        }
        model.addAttribute("icons", AccessoryServiceAdmin.ICONS);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        return "backoffice/accessory-services";
    }

    /** A row other than item 0, or 404. */
    private AccessoryServiceAdmin.Row editable(String id) {
        if (!id.matches("^[0-9]{1,6}$")) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        return services.find(Integer.parseInt(id))
                .filter(row -> !row.locked())
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
    }

    /** The form as typed: text by input name, the three places as "si" or "". */
    private static Map<String, String> values(Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : new String[] {"nome", "descrizione", "icona", "suggerimento"}) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        for (String place : new String[] {"ordini", "sito", "preventivo"}) {
            values.put(place, form.containsKey(place) ? "si" : "");
        }
        return values;
    }

    private static Map<String, String> values(AccessoryServiceAdmin.Row row) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("nome", row.label());
        values.put("descrizione", row.description() == null ? "" : row.description());
        values.put("icona", row.icon() == null ? "" : row.icon());
        values.put("suggerimento", row.quoteHint() == null ? "" : row.quoteHint());
        values.put("ordini", row.inOrders() ? "si" : "");
        values.put("sito", row.onSite() ? "si" : "");
        values.put("preventivo", row.inQuote() ? "si" : "");
        return values;
    }

    private void requireFlag() {
        if (!flags.isEnabled(FlagKey.ADMIN_ENUMERATIONS)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
    }
}
