package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.StaffAccountService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditQuery;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.audit.AuditView;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * «Registro delle attività» (ENH-13, flag {@code admin.audit}; ADR 0040), ADMIN only, under
 * «Impostazioni»: the audit log written since M1 (NFR-02), newest first, {@value #PAGE_SIZE} a
 * page, filtered by who (a staff user, customers, the system), action, object (type and id) and
 * days (Europe/Rome). Each entry shows ids, references and the changed fields with their
 * non-personal values; the personal values recorded apart are never shown. 404 while the flag is
 * off.
 */
@Controller
public class BackofficeAuditController {

    static final int PAGE_SIZE = 50;
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final AuditService audit;
    private final StaffAccountService staffAccounts;
    private final FeatureFlags flags;

    public BackofficeAuditController(AuditService audit, StaffAccountService staffAccounts, FeatureFlags flags) {
        this.audit = audit;
        this.staffAccounts = staffAccounts;
        this.flags = flags;
    }

    @GetMapping(BackofficeRoutes.AUDIT)
    public String log(
            @RequestParam(name = "chi", required = false) String who,
            @RequestParam(name = "azione", required = false) String action,
            @RequestParam(name = "oggetto", required = false) String entityType,
            @RequestParam(name = "id", required = false) String entityId,
            @RequestParam(name = "dal", required = false) String from,
            @RequestParam(name = "al", required = false) String to,
            @RequestParam(name = "pagina", required = false) Integer pageNumber,
            Model model) {
        if (!flags.isEnabled(FlagKey.ADMIN_AUDIT)) {
            throw new ClientErrorException(ErrorCode.NOT_FOUND);
        }
        List<String> actions = audit.actions();
        List<String> entityTypes = audit.entityTypes();
        Map<Long, StaffAccountService.StaffUserView> staff = staffAccounts.listAll().stream()
                .collect(Collectors.toMap(
                        StaffAccountService.StaffUserView::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("chi", ItalianFormats.trim(who));
        filters.put("azione", actions.contains(ItalianFormats.trim(action)) ? ItalianFormats.trim(action) : "");
        filters.put(
                "oggetto",
                entityTypes.contains(ItalianFormats.trim(entityType)) ? ItalianFormats.trim(entityType) : "");
        filters.put("id", ItalianFormats.trim(entityId).matches("^[0-9]{1,18}$") ? ItalianFormats.trim(entityId) : "");
        LocalDate fromDay = day(from);
        LocalDate toDay = day(to);
        filters.put("dal", fromDay == null ? "" : fromDay.toString());
        filters.put("al", toDay == null ? "" : toDay.toString());

        AuditActorType actorType = null;
        Long actorId = null;
        String chi = filters.get("chi");
        if (chi.startsWith("staff-") && chi.substring(6).matches("^[0-9]{1,18}$")) {
            actorType = AuditActorType.STAFF;
            actorId = Long.parseLong(chi.substring(6));
        } else if (chi.equals("STAFF") || chi.equals("CUSTOMER") || chi.equals("SYSTEM")) {
            actorType = AuditActorType.valueOf(chi);
        } else {
            filters.put("chi", "");
        }
        AuditQuery query = new AuditQuery(
                actorType,
                actorId,
                filters.get("azione").isEmpty() ? null : filters.get("azione"),
                filters.get("oggetto").isEmpty() ? null : filters.get("oggetto"),
                filters.get("id").isEmpty() ? null : Long.parseLong(filters.get("id")),
                fromDay == null ? null : start(fromDay),
                toDay == null ? null : start(toDay.plusDays(1)));
        int page = pageNumber == null || pageNumber < 1 ? 0 : pageNumber - 1;
        Page<AuditView> result = audit.search(query, page, PAGE_SIZE);

        model.addAttribute("page", result);
        model.addAttribute("filters", filters);
        model.addAttribute("actions", actions);
        model.addAttribute("entityTypes", entityTypes);
        model.addAttribute("staffUsers", staff.values());
        model.addAttribute("staffNames", staff);
        return "backoffice/audit";
    }

    /** A day typed as {@code yyyy-MM-dd} (the date input); null when empty or not a date. */
    private static LocalDate day(String value) {
        String text = ItalianFormats.trim(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ROME).toInstant();
    }
}
