package it.logisticasanchez.web.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface OrderInternalNoteRepository extends JpaRepository<OrderInternalNote, Long> {

    /** Erasure (NFR-03): the staff notes of every order of a customer; returns how many were removed. */
    @Modifying
    @Query(
            value = "delete from order_internal_note n using shipment_order o"
                    + " where n.order_id = o.id and o.customer_id = :customerId",
            nativeQuery = true)
    int deleteByCustomerId(long customerId);
}
