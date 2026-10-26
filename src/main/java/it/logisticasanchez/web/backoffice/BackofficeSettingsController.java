package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.StaffAccountService;
import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffTwoFactorService;
import it.logisticasanchez.web.platform.flags.FeatureFlagService;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.privacy.PrivacyService;
import it.logisticasanchez.web.reports.ReportRecipientService;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * «Impostazioni», ADMIN only (chapter 10 §2; ADR 0025, 0016, 0024): the M2 feature flags (one
 * form per flag, each with what it does and whether it is built: built flags first, and only
 * those can be turned on), the report recipients allow-list, the staff accounts (creation sends
 * the set-password link, EML-S1; with {@code security.adminTotp} on, whether each has two-factor
 * set up and «Azzera» for a lost phone, ENH-09), the privacy tools of NFR-03 (export as JSON,
 * erasure by anonymisation behind a confirmation page) and, with {@code admin.audit} on, the links to
 * the audit log viewer (ENH-13), the site notices (ENH-17) and the office closures (ENH-18). Every action is audited by the service it calls.
 */
@Controller
public class BackofficeSettingsController {

    private final FeatureFlagService flags;
    private final ReportRecipientService recipients;
    private final StaffAccountService staffAccounts;
    private final StaffTwoFactorService twoFactor;
    private final PrivacyService privacy;

    public BackofficeSettingsController(
            FeatureFlagService flags,
            ReportRecipientService recipients,
            StaffAccountService staffAccounts,
            StaffTwoFactorService twoFactor,
            PrivacyService privacy) {
        this.flags = flags;
        this.recipients = recipients;
        this.staffAccounts = staffAccounts;
        this.twoFactor = twoFactor;
        this.privacy = privacy;
    }

    @GetMapping(BackofficeRoutes.SETTINGS)
    public String settings(Model model) {
        return settingsPage(model, "", "", null);
    }

    @PostMapping(BackofficeRoutes.SETTINGS_FLAG)
    public String toggleFlag(
            @RequestParam(name = "chiave", required = false) String key,
            @RequestParam(name = "attiva", required = false) String enabled,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        FlagKey flag = FlagKey.ofKey(key == null ? "" : key)
                .orElseThrow(() -> new RuleViolationException("Funzionalità sconosciuta."));
        boolean on = "1".equals(enabled) || "true".equals(enabled);
        if (on && !flag.built()) {
            redirect.addFlashAttribute(
                    "flashError", "La funzionalità «" + flag.key() + "» non è ancora sviluppata: non si può attivare.");
            return "redirect:" + BackofficeRoutes.SETTINGS;
        }
        flags.toggle(flag, on, StaffSession.of(authentication).requireId());
        redirect.addFlashAttribute(
                "flash", "Funzionalità «" + flag.key() + "» " + (on ? "attivata" : "disattivata") + ".");
        return "redirect:" + BackofficeRoutes.SETTINGS;
    }

    @PostMapping(BackofficeRoutes.SETTINGS_RECIPIENTS)
    public String addRecipient(
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "descrizione", required = false) String label,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        try {
            recipients.add(StaffSession.of(authentication).requireId(), email, label);
        } catch (RuleViolationException e) {
            return settingsPage(model, ItalianFormats.trim(email), ItalianFormats.trim(label), e.detail());
        }
        redirect.addFlashAttribute("flash", "Destinatario aggiunto.");
        return "redirect:" + BackofficeRoutes.SETTINGS;
    }

    @PostMapping(BackofficeRoutes.SETTINGS_RECIPIENT_REMOVE)
    public String removeRecipient(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        recipients.remove(StaffSession.of(authentication).requireId(), id);
        redirect.addFlashAttribute("flash", "Destinatario rimosso.");
        return "redirect:" + BackofficeRoutes.SETTINGS;
    }

    @GetMapping(BackofficeRoutes.STAFF_USERS)
    public String staffUsers(Model model) {
        return staffUsersPage(model, Map.of(), null);
    }

    @PostMapping(BackofficeRoutes.STAFF_USERS)
    public String createStaffUser(
            @RequestParam Map<String, String> form,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : new String[] {"username", "nome", "email", "ruolo"}) {
            values.put(name, ItalianFormats.trim(form.get(name)));
        }
        try {
            if (!values.get("username").matches("^[a-zA-Z0-9._-]{3,100}$")) {
                throw new RuleViolationException(
                        "Lo username ha da 3 a 100 caratteri: lettere, cifre, punto, trattino o trattino basso.");
            }
            if (values.get("nome").isEmpty() || values.get("nome").length() > 100) {
                throw new RuleViolationException("Inserisci il nome (al massimo 100 caratteri).");
            }
            if (!ItalianFormats.isEmail(values.get("email"))) {
                throw new RuleViolationException(
                        "Inserisci un indirizzo e-mail valido: riceve il link per la password.");
            }
            StaffRole role;
            try {
                role = StaffRole.valueOf(values.get("ruolo"));
            } catch (IllegalArgumentException e) {
                throw new RuleViolationException("Scegli il ruolo.");
            }
            staffAccounts.createByAdmin(
                    StaffSession.of(authentication).requireId(),
                    values.get("username"),
                    values.get("nome"),
                    values.get("email"),
                    role);
        } catch (StaffAccountService.UsernameTakenException e) {
            return staffUsersPage(model, values, "Esiste già un utente con questo username.");
        } catch (StaffAccountService.EmailTakenException e) {
            return staffUsersPage(model, values, "Esiste già un utente con questa e-mail.");
        } catch (RuleViolationException e) {
            return staffUsersPage(model, values, e.detail());
        }
        redirect.addFlashAttribute(
                "flash",
                "Utente «" + values.get("username") + "» creato: il link per impostare la password è stato inviato.");
        return "redirect:" + BackofficeRoutes.STAFF_USERS;
    }

    @PostMapping(BackofficeRoutes.STAFF_USER_TWO_FACTOR_RESET)
    public String resetTwoFactor(
            @PathVariable("id") long id,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            RedirectAttributes redirect) {
        if (!twoFactor.required()) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        StaffAccountService.StaffUserView user = staffAccounts.listAll().stream()
                .filter(u -> u.id() == id)
                .findFirst()
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        twoFactor.reset(StaffSession.of(authentication).requireId(), user.id());
        redirect.addFlashAttribute(
                "flash",
                "Verifica in due passaggi di «" + user.username()
                        + "» azzerata: al prossimo accesso la configura di nuovo.");
        return "redirect:" + BackofficeRoutes.STAFF_USERS;
    }

    @GetMapping(BackofficeRoutes.PRIVACY)
    public String privacy(Model model) {
        return privacyPage(model, "", null, null);
    }

    @PostMapping(BackofficeRoutes.PRIVACY_LOOKUP)
    public String privacyLookup(@RequestParam(name = "email", required = false) String email, Model model) {
        try {
            return privacyPage(model, ItalianFormats.trim(email), privacy.lookup(email), null);
        } catch (RuleViolationException e) {
            return privacyPage(model, ItalianFormats.trim(email), null, e.detail());
        }
    }

    @PostMapping(BackofficeRoutes.PRIVACY_EXPORT)
    public ResponseEntity<byte[]> privacyExport(
            @RequestParam(name = "email", required = false) String email,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        String document = privacy.export(StaffSession.of(authentication).requireId(), email);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("dati-personali.json")
                                .build()
                                .toString())
                .body(document.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(BackofficeRoutes.PRIVACY_ANONYMISE)
    public String privacyAnonymise(
            @RequestParam(name = "email", required = false) String email,
            @CurrentSecurityContext(expression = "authentication") Authentication authentication,
            Model model,
            RedirectAttributes redirect) {
        try {
            PrivacyService.Outcome outcome =
                    privacy.anonymise(StaffSession.of(authentication).requireId(), email);
            redirect.addFlashAttribute(
                    "flash",
                    "Dati anonimizzati: "
                            + (outcome.customerId() == null ? "nessun cliente" : "cliente n. " + outcome.customerId())
                            + ", " + outcome.leads() + " richieste dal sito, " + outcome.outboxRows() + " e-mail, "
                            + outcome.notes() + " note interne"
                            + (outcome.addresses() > 0 ? ", " + outcome.addresses() + " destinatari in rubrica" : "")
                            + (outcome.quotes() > 0 ? ", " + outcome.quotes() + " preventivi" : "")
                            + (outcome.changeRequests() > 0
                                    ? ", " + outcome.changeRequests() + " richieste di modifica dei dati"
                                    : "")
                            + ".");
        } catch (RuleViolationException e) {
            return privacyPage(model, ItalianFormats.trim(email), null, e.detail());
        }
        return "redirect:" + BackofficeRoutes.PRIVACY;
    }

    private String settingsPage(Model model, String email, String label, String error) {
        model.addAttribute("flags", flagRows(flags.all()));
        model.addAttribute("recipients", recipients.list());
        model.addAttribute("configuredRecipients", recipients.configured());
        model.addAttribute("email", email);
        model.addAttribute("descrizione", label);
        model.addAttribute("error", error);
        model.addAttribute("auditEnabled", flags.isEnabled(FlagKey.ADMIN_AUDIT)); // ENH-13
        model.addAttribute("noticesEnabled", flags.isEnabled(FlagKey.SITE_NOTICE_BANNER)); // ENH-17
        model.addAttribute("closuresEnabled", flags.isEnabled(FlagKey.SITE_OPEN_NOW)); // ENH-18
        return "backoffice/settings";
    }

    /** The flags table: built flags first, then the others, each group in requirement order. */
    static List<FlagRow> flagRows(Map<FlagKey, Boolean> values) {
        return values.entrySet().stream()
                .map(e -> new FlagRow(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing((FlagRow row) -> !row.built())
                        .thenComparing(row -> row.key().ordinal()))
                .toList();
    }

    /** One row of the flags table; a flag not built yet can only be turned off (should it be on). */
    public record FlagRow(FlagKey key, boolean enabled) {

        public boolean built() {
            return key.built();
        }

        public boolean toggleable() {
            return key.built() || enabled;
        }
    }

    private String staffUsersPage(Model model, Map<String, String> values, String error) {
        model.addAttribute("users", staffAccounts.listAll());
        model.addAttribute("values", values);
        model.addAttribute("roles", StaffRole.values());
        model.addAttribute("error", error);
        model.addAttribute("twoFactor", twoFactor.required()); // ENH-09: false = the M1 page
        return "backoffice/staff-users";
    }

    private String privacyPage(Model model, String email, PrivacyService.Subject subject, String error) {
        model.addAttribute("email", email);
        model.addAttribute("subject", subject);
        model.addAttribute("error", error);
        return "backoffice/privacy";
    }
}
