package it.logisticasanchez.web.site.forms;

import it.logisticasanchez.web.platform.retention.RetentionJob;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Q-18: a lead is anonymised once its {@code retain_until} (set at insert from
 * {@code ls.retention.leads-months}) has passed; the row stays for statistics without any
 * personal value.
 */
@Component
public class LeadRetentionJob implements RetentionJob {

    private final LeadService leads;

    public LeadRetentionJob(LeadService leads) {
        this.leads = leads;
    }

    @Override
    public String name() {
        return "leads";
    }

    @Override
    public int run(Instant now) {
        return leads.anonymiseExpired(now);
    }
}
