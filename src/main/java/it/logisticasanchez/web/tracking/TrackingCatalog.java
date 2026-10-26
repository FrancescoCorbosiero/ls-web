package it.logisticasanchez.web.tracking;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two seeded enumerations as the other modules read them (ADM-10; chapter 10 §2 "modules
 * talk through services"): the six tracking states and the accessory services, by id and in
 * display order. Item 0 of the services is «Nessun servizio accessorio»: an order stores null
 * for it (V005). ENH-14 (ADR 0043): the order forms offer only the services of the order; the
 * public pages read their own lists ({@link #siteServices()}, {@link #quoteExtras()}).
 */
@Service
public class TrackingCatalog {

    private final TrackingStateRepository states;
    private final AccessoryServiceRepository services;

    public TrackingCatalog(TrackingStateRepository states, AccessoryServiceRepository services) {
        this.states = states;
        this.services = services;
    }

    @Transactional(readOnly = true)
    public List<StateView> states() {
        return states.findAllByOrderByIdAsc().stream()
                .map(s -> new StateView(s.getId(), s.getName(), s.getLabel(), s.getPublicTitle(), s.getStepLabel()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<StateView> state(int id) {
        return states.findById((short) id)
                .map(s -> new StateView(s.getId(), s.getName(), s.getLabel(), s.getPublicTitle(), s.getStepLabel()));
    }

    /** The active services the order forms offer, the «none» entry first (ADM-10, OP-R6). */
    @Transactional(readOnly = true)
    public List<ServiceView> activeServices() {
        return activeServices(null);
    }

    /**
     * As {@link #activeServices()}, plus the service already chosen on the form ({@code chosen}, the
     * raw field value) when the office has since deactivated it (ENH-14): editing an order never
     * changes its service behind the user's back.
     */
    @Transactional(readOnly = true)
    public List<ServiceView> activeServices(String chosen) {
        String value = chosen == null ? "" : chosen.trim();
        Integer kept = value.matches("^[0-9]{1,6}$") ? Integer.valueOf(value) : null;
        return services.findAllByOrderBySortOrderAsc().stream()
                .filter(AccessoryService::isInOrders)
                .filter(s -> s.isActive() || s.getId().equals(kept))
                .map(TrackingCatalog::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ServiceView> service(int id) {
        return services.findById(id).map(TrackingCatalog::view);
    }

    /** A service an order may carry, active or not: never one of the public lists only (ENH-14). */
    @Transactional(readOnly = true)
    public Optional<ServiceView> orderService(int id) {
        return services.findById(id).filter(AccessoryService::isInOrders).map(TrackingCatalog::view);
    }

    /** ENH-14: the active services of «Servizi accessori» on {@code /servizi/}, in order. */
    @Transactional(readOnly = true)
    public List<SiteService> siteServices() {
        return services.findAllByOrderBySortOrderAsc().stream()
                .filter(s -> s.isActive() && s.isOnSite())
                .map(s -> new SiteService(s.getLabel(), s.getDescription(), s.getIcon()))
                .toList();
    }

    /** ENH-14: the active extras of the quote wizard, in order; the label is the field value (PUB-06). */
    @Transactional(readOnly = true)
    public List<QuoteExtra> quoteExtras() {
        return services.findAllByOrderBySortOrderAsc().stream()
                .filter(s -> s.isActive() && s.isInQuote())
                .map(s -> new QuoteExtra(s.getLabel(), s.getQuoteHint()))
                .toList();
    }

    private static ServiceView view(AccessoryService s) {
        return new ServiceView(s.getId(), s.getLabel(), s.isNone());
    }

    /** A tracking state; {@code stepLabel} is the short step name of the progress bar (null for NON_GESTITO). */
    public record StateView(short id, String name, String label, String publicTitle, String stepLabel) {}

    public record ServiceView(int id, String label, boolean none) {}

    /** A card of «Servizi accessori»: {@code icon} is a symbol of {@code /img/icons.svg}. */
    public record SiteService(String label, String description, String icon) {}

    /** An extra of the quote wizard: {@code hint} is the short line under it, or null. */
    public record QuoteExtra(String label, String hint) {}
}
