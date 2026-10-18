package it.logisticasanchez.web.platform.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link DomainEvent}s in process, synchronously, inside the publishing transaction
 * (a subscriber that must not roll back the business action uses
 * {@code @TransactionalEventListener}). Owning modules publish; nothing subscribes in M1/M2.
 */
@Component
public class DomainEvents {

    private final ApplicationEventPublisher publisher;

    public DomainEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
