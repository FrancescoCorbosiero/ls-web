package it.logisticasanchez.web.reports;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.ReportsProperties;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report recipients allow-list of ADM-09 / Q-08: the office address is always allowed, so
 * are the addresses an ADMIN adds here and the ones of {@code ls.reports.recipients.allow-list}
 * (a configured seed for environments without a back office yet). Every change is audited; the
 * addresses themselves go to the audit detail, never to the log row.
 */
@Service
public class ReportRecipientService {

    private final ReportRecipientRepository recipients;
    private final ReportsProperties settings;
    private final BusinessProperties business;
    private final AuditService audit;
    private final Clock clock;

    public ReportRecipientService(
            ReportRecipientRepository recipients,
            ReportsProperties settings,
            BusinessProperties business,
            AuditService audit,
            Clock clock) {
        this.recipients = recipients;
        this.settings = settings;
        this.business = business;
        this.audit = audit;
        this.clock = clock;
    }

    /** A row of the allow-list as the settings page shows it. */
    public record RecipientView(long id, String email, String label) {}

    @Transactional(readOnly = true)
    public List<RecipientView> list() {
        return recipients.findAllByOrderByEmailAsc().stream()
                .map(r -> new RecipientView(r.getId(), r.getEmail(), r.getLabel()))
                .toList();
    }

    /** The configured addresses ({@code ls.reports.recipients.allow-list}), normalised. */
    public List<String> configured() {
        return settings.recipients().allowList().stream() // Q-08
                .map(ReportRecipientService::normalise)
                .filter(a -> !a.isEmpty())
                .toList();
    }

    /** True when a report e-mail may go to this address: the office, an allow-listed row or a configured address. */
    @Transactional(readOnly = true)
    public boolean allows(String email) {
        String normalised = normalise(email);
        return normalised.equals(normalise(business.officeEmail()))
                || configured().contains(normalised)
                || recipients.findByEmail(normalised).isPresent();
    }

    /** ADMIN only: adds an address (trimmed, lower-case, must look like an e-mail, not already present). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RecipientView add(long adminId, String email, String label) {
        String normalised = normalise(email);
        if (!ItalianFormats.isEmail(normalised)) {
            throw new RuleViolationException("Inserisci un indirizzo e-mail valido.");
        }
        if (allows(normalised)) {
            throw new RuleViolationException("L'indirizzo è già tra i destinatari autorizzati.");
        }
        String text = ItalianFormats.trim(label);
        if (text.length() > 100) {
            throw new RuleViolationException("La descrizione può avere al massimo 100 caratteri.");
        }
        ReportRecipient row = recipients.save(
                new ReportRecipient(normalised, text.isEmpty() ? null : text, adminId, clock.instant()));
        audit.record(AuditEntry.of(AuditActorType.STAFF, adminId, "report.recipient.add")
                .entity("report_recipient", row.getId(), null)
                .personal(null, Map.of("email", normalised)));
        return new RecipientView(row.getId(), row.getEmail(), row.getLabel());
    }

    /** ADMIN only: removes a row; unknown ids are ignored. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void remove(long adminId, long id) {
        recipients.findById(id).ifPresent(row -> {
            recipients.delete(row);
            audit.record(AuditEntry.of(AuditActorType.STAFF, adminId, "report.recipient.remove")
                    .entity("report_recipient", row.getId(), null)
                    .personal(Map.of("email", row.getEmail()), null));
        });
    }

    static String normalise(String email) {
        return ItalianFormats.trim(email).toLowerCase(Locale.ROOT);
    }
}
