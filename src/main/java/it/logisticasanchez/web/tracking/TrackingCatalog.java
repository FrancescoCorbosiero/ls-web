package it.logisticasanchez.web.tracking;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two seeded enumerations as the other modules read them (ADM-10; chapter 10 §2 "modules
 * talk through services"): the six tracking states and the accessory services, by id and in
 * display order. Item 0 of the services is «Nessun servizio accessorio»: an order stores null
 * for it (V005).
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
                .map(s -> new StateView(s.getId(), s.getName(), s.getLabel(), s.getPublicTitle()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<StateView> state(int id) {
        return states.findById((short) id)
                .map(s -> new StateView(s.getId(), s.getName(), s.getLabel(), s.getPublicTitle()));
    }

    /** The active services the wizard offers, the «none» entry first (ADM-10, OP-R6). */
    @Transactional(readOnly = true)
    public List<ServiceView> activeServices() {
        return services.findAllByOrderBySortOrderAsc().stream()
                .filter(AccessoryService::isActive)
                .map(s -> new ServiceView(s.getId(), s.getLabel(), s.isNone()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ServiceView> service(int id) {
        return services.findById(id).map(s -> new ServiceView(s.getId(), s.getLabel(), s.isNone()));
    }

    public record StateView(short id, String name, String label, String publicTitle) {}

    public record ServiceView(int id, String label, boolean none) {}
}
