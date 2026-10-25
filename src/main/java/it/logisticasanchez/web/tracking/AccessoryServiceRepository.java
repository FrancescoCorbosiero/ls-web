package it.logisticasanchez.web.tracking;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessoryServiceRepository extends JpaRepository<AccessoryService, Integer> {

    List<AccessoryService> findAllByOrderBySortOrderAsc();
}
