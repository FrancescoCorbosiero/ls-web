package it.logisticasanchez.web.tracking;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessoryServiceRepository extends JpaRepository<AccessoryService, Integer> {

    List<AccessoryService> findAllByOrderBySortOrderAsc();

    /** EP-20 order: the legacy ids first, as the enumeration declared them, then the rows added later (ENH-14). */
    List<AccessoryService> findAllByOrderByIdAsc();
}
