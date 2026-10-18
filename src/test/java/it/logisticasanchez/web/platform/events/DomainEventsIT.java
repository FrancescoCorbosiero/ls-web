package it.logisticasanchez.web.platform.events;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/** The in-process event publisher reaches a subscriber (the only extension point of the "Later" backlog). */
@SpringBootTest
@Import(DomainEventsIT.Subscriber.class)
class DomainEventsIT extends AbstractPostgresIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Subscriber {
        final List<DomainEvent> received = new CopyOnWriteArrayList<>();

        @Bean
        Subscriber self() {
            return this;
        }

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
    void publishedEventsReachSubscribers() {
        events.publish(new DomainEvent.OrderApproved(10L, 2L));
        assertThat(subscriber.received).containsExactly(new DomainEvent.OrderApproved(10L, 2L));
    }
}
