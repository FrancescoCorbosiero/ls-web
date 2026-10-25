package it.logisticasanchez.web.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * ADR 0025 "Extension points": each named domain event is published once in process and
 * reaches a subscriber — the only hook of the chapter 09 "Later" backlog; no subscriber exists
 * in the application itself.
 */
@SpringBootTest
@Import(DomainEventsIT.Subscriber.class)
class DomainEventsIT extends AbstractPostgresIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Subscriber {
        final List<DomainEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DomainEvent event) {
            received.add(event);
        }
    }

    @Autowired
    private DomainEvents events;

    @Autowired
    private Subscriber subscriber;

    @Test
    void eachNamedEventIsPublishedOnceAndReachesTheSubscriber() {
        List<DomainEvent> published = List.of(
                new DomainEvent.CustomerVerified(1L),
                new DomainEvent.CustomerApproved(1L, 2L),
                new DomainEvent.CustomerDeclined(1L, 2L),
                new DomainEvent.CustomerChanged(1L, 2L),
                new DomainEvent.OrderCreated(10L, 1L),
                new DomainEvent.OrderApproved(10L, 2L),
                new DomainEvent.OrderDeclined(10L, 2L),
                new DomainEvent.OrderChanged(10L),
                new DomainEvent.OrderCancelled(10L),
                new DomainEvent.TrackingChanged(10L, 3, 2L),
                new DomainEvent.LeadStored(5L));
        assertThat(published.stream().map(Object::getClass).distinct())
                .as("one instance of every event type of the sealed interface")
                .hasSize(DomainEvent.class.getPermittedSubclasses().length);

        published.forEach(events::publish);

        assertThat(subscriber.received).containsExactlyElementsOf(published);
    }
}
