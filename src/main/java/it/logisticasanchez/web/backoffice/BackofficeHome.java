package it.logisticasanchez.web.backoffice;

import it.logisticasanchez.web.auth.StaffHomePage;
import it.logisticasanchez.web.orders.OrderSearch;
import it.logisticasanchez.web.orders.OrderService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * The back-office home «Panoramica» (ADR 0029): what waits for staff — orders and customers to
 * approve (the ADM-04 and ADM-02 queues), shipments still travelling (ADM-06) —, the quick
 * searches by code, the shortcuts to a new order or customer (ADM-11, ADM-12) and the latest
 * orders. Counts of the existing queues only: the dashboard of ENH-08 stays behind its flag.
 */
@Component
public class BackofficeHome implements StaffHomePage {

    static final int RECENT = 6;

    private final BackofficeModelAttributes attributes;
    private final OrderService orders;

    public BackofficeHome(BackofficeModelAttributes attributes, OrderService orders) {
        this.attributes = attributes;
        this.orders = orders;
    }

    @Override
    public String render(Authentication authentication, Model model) {
        model.addAttribute("staff", StaffSession.of(authentication));
        model.addAttribute("queue", attributes.queueNow());
        model.addAttribute("inTransit", orders.countInTransit());
        model.addAttribute(
                "recentOrders", orders.search(OrderSearch.none(), 0, RECENT).getContent());
        return "backoffice/home";
    }
}
