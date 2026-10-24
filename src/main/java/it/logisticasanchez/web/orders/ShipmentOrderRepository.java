package it.logisticasanchez.web.orders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ShipmentOrderRepository extends JpaRepository<ShipmentOrder, Long> {

    List<ShipmentOrder> findByCustomerIdOrderBySubmittedAtDesc(long customerId);

    Optional<ShipmentOrder> findByIdempotencyKey(UUID idempotencyKey);

    /** The customer's order with this code or temporary code, trimmed and ignoring case (chapter 08 §2.5). */
    @Query("select o from ShipmentOrder o where o.customerId = :customerId"
            + " and (lower(o.tempCode) = :code or lower(o.code) = :code)")
    Optional<ShipmentOrder> findByCustomerIdAndAnyCode(
            @Param("customerId") long customerId, @Param("code") String code);

    @Query("select count(o) > 0 from ShipmentOrder o where lower(o.code) = :code")
    boolean existsByCodeNormalised(@Param("code") String code);

    List<ShipmentOrder> findByStatusOrderBySubmittedAtAsc(OrderStatus status);
}
