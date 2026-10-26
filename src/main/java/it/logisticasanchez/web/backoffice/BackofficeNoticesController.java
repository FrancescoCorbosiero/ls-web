package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.site.notices.SiteNoticeService;
import java.time.Clock;
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
 * «Avvisi sul sito» (ENH-17, flag {@code site.noticeBanner}; ADR 0041), ADMIN only, under
 * «Impostazioni»: the notices of the public site — in force, to come, ended — and the form of a
 * new one (text, optional link, level, optional period in Europe/Rome); «Chiudi ora» ends one,
 * «Elimina» removes it. Rules and the cache of the public pages live in {@link SiteNoticeService}.
 * 404 while the flag is off.
 */
@Controller
public class BackofficeNoticesController {

    private static final String[] FIELDS = {"testo", "link", "etichetta", "livello", "dal", "al"};

    private final SiteNoticeService notices;
    private final FeatureFlags flags;
    private final Clock clock;

    public BackofficeNoticesController(SiteNoticeService notices, FeatureFlags flags, Clock clock) {
        this.notices = notices;
        this.flags = flags;
        this.clock = clock;
    }

    @GetMapping(BackofficeRoutes.NOTICES)
    public String list(Model model) {
        requireFlag();
        return page(model, blank(), Map.of());
    }

    @PostMapping(BackofficeRoutes.NOTICES)
    public String create(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        requireFlag();
        SiteNoticeService.Result result =
                notices.create(StaffSession.of(authentication).requireId(), form);
        if (!result.valid()) {
            return page(model, typed(form), result.errors());
        }
        redirect.addFlashAttribute("flash", "Avviso salvato: le pagine del sito lo mostrano nel periodo scelto.");
        return "redirect:" + BackofficeRoutes.NOTICES;
    }

    @PostMapping(BackofficeRoutes.NOTICE_END)
    public String end(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        boolean ended = notices.end(StaffSession.of(authentication).requireId(), id);
        redirect.addFlashAttribute(
                ended ? "flash" : "flashError",
                ended ? "Avviso chiuso: non compare più sul sito." : "L'avviso era già concluso.");
        return "redirect:" + BackofficeRoutes.NOTICES;
    }

    @PostMapping(BackofficeRoutes.NOTICE_DELETE)
    public String delete(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        requireFlag();
        if (!notices.delete(StaffSession.of(authentication).requireId(), id)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        redirect.addFlashAttribute("flash", "Avviso eliminato.");
        return "redirect:" + BackofficeRoutes.NOTICES;
    }

    private String page(Model model, Map<String, String> values, Map<String, String> errors) {
        model.addAttribute("notices", notices.all());
        model.addAttribute("now", clock.instant());
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        return "backoffice/notices";
    }

    private void requireFlag() {
        if (!flags.isEnabled(FlagKey.SITE_NOTICE_BANNER)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
    }

    private static Map<String, String> blank() {
        Map<String, String> values = typed(Map.of());
        values.put("livello", "INFO");
        return values;
    }

    private static Map<String, String> typed(Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : FIELDS) {
            values.put(field, ItalianFormats.trim(form.get(field)));
        }
        return values;
    }
}
