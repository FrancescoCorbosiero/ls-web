package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.StaffHomePage;
import it.logisticasanchez.web.orders.OrderSearch;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.config.BackofficeProperties;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.validation.ItalianFormats;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * The back-office home «Panoramica» (ADR 0029): what waits for staff — orders and customers to
 * approve (the ADM-04 and ADM-02 queues), shipments still travelling (ADM-06) —, the quick
 * searches by code, the shortcuts to a new order or customer (ADM-11, ADM-12) and the latest
 * orders. With {@code admin.dashboard} on (ENH-08, ADR 0035) also «Oggi»: the orders to approve,
 * the goods to pick up and the shipments on their way for more than
 * {@code ls.backoffice.today-transit-days} days, oldest first.
 */
@Component
public class BackofficeHome implements StaffHomePage {

    static final int RECENT = 6;
    /** Rows of each «Oggi» group; the rest is counted. */
    static final int TODAY_ROWS = 8;

    private final BackofficeModelAttributes attributes;
    private final OrderService orders;
    private final FeatureFlags flags;
    private final BackofficeProperties settings;
    private final Clock clock;

    public BackofficeHome(
            BackofficeModelAttributes attributes,
            OrderService orders,
            FeatureFlags flags,
            BackofficeProperties settings,
            Clock clock) {
        this.attributes = attributes;
        this.orders = orders;
        this.flags = flags;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public String render(Authentication authentication, Model model) {
        model.addAttribute("staff", StaffSession.of(authentication));
        model.addAttribute("queue", attributes.queueNow());
        model.addAttribute("inTransit", orders.countInTransit());
        model.addAttribute(
                "recentOrders", orders.search(OrderSearch.none(), 0, RECENT).getContent());
        model.addAttribute("today", flags.isEnabled(FlagKey.ADMIN_DASHBOARD) ? today() : null);
        return "backoffice/home";
    }

    /** «Oggi»: each group's first rows, oldest first, and how many there are. */
    Today today() {
        List<OrderView> pending = orders.pendingApproval();
        int days = settings.todayTransitDays();
        LocalDate since = LocalDate.now(clock.withZone(ItalianFormats.ROME)).minusDays(days);
        Page<OrderView> stuck =
                orders.travellingSince(since.atStartOfDay(ItalianFormats.ROME).toInstant(), TODAY_ROWS);
        Page<OrderView> toPickUp = orders.toPickUp(TODAY_ROWS);
        return new Today(
                pending.subList(0, Math.min(TODAY_ROWS, pending.size())),
                pending.size(),
                toPickUp.getContent(),
                toPickUp.getTotalElements(),
                stuck.getContent(),
                stuck.getTotalElements(),
                days,
                since);
    }

    /** The three groups of «Oggi» with their totals; {@code since}: the first day not counted as «too long». */
    public record Today(
            List<OrderView> toApprove,
            long toApproveCount,
            List<OrderView> toPickUp,
            long toPickUpCount,
            List<OrderView> stuck,
            long stuckCount,
            int days,
            LocalDate since) {}
}
