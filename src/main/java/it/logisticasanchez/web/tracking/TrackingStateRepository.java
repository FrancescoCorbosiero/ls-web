package it.logisticasanchez.web.tracking;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackingStateRepository extends JpaRepository<TrackingState, Short> {

    List<TrackingState> findAllByOrderByIdAsc();
}
