package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.site.hours.OfficeClosureService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * «Chiusure dell'ufficio» (ENH-18, flag {@code site.openNow}; ADR 0042), ADMIN only, under
 * «Impostazioni»: the closure days to come with «Rimuovi», and the form that adds a day or a
 * range with an optional reason. The public pages turn them into «Chiuso ora» next to the hours.
 * 404 while the flag is off.
 */
@Controller
public class BackofficeClosuresController {

    private final OfficeClosureService closures;
    private final FeatureFlags flags;

    public BackofficeClosuresController(OfficeClosureService closures, FeatureFlags flags) {
        this.closures = closures;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.CLOSURES)
    public String list(Model model) {
        requireFlag();
        return page(model, Map.of("dal", "", "al", "", "motivo", ""), Map.of());
    }

    @PostMapping(BackofficeRoutes.CLOSURES)
    public String add(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        requireFlag();
        Map<String, String> errors =
                closures.add(StaffSession.of(authentication).requireId(), form);
        if (!errors.isEmpty()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String field : new String[] {"dal", "al", "motivo"}) {
                values.put(field, ItalianFormats.trim(form.get(field)));
            }
            return page(model, values, errors);
        }
        redirect.addFlashAttribute("flash", "Chiusura aggiunta: il sito ne tiene conto accanto all'orario.");
        return "redirect:" + BackofficeRoutes.CLOSURES;
    }

    @PostMapping(BackofficeRoutes.CLOSURE_REMOVE)
    public String remove(
            @PathVariable("giorno") String day,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        LocalDate date;
        try {
            date = LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        if (!closures.remove(StaffSession.of(authentication).requireId(), date)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        redirect.addFlashAttribute("flash", "Chiusura rimossa: quel giorno vale l'orario di sempre.");
        return "redirect:" + BackofficeRoutes.CLOSURES;
    }

    private String page(Model model, Map<String, String> values, Map<String, String> errors) {
        model.addAttribute("closures", closures.upcoming());
        model.addAttribute("openNow", closures.isOpenNow());
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        return "backoffice/closures";
    }

    private void requireFlag() {
        if (!flags.isEnabled(FlagKey.SITE_OPEN_NOW)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
    }
}
