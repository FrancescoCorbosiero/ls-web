package it.logisticasanchez.web.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {

    List<TrackingEvent> findByOrderIdOrderByOccurredAtAscIdAsc(long orderId);
}
