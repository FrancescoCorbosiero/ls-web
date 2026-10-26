package it.logisticasanchez.web.customers;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The data change requests (ENH-16); read only through {@link CustomerChangeService}. */
interface CustomerChangeRequestRepository extends JpaRepository<CustomerChangeRequest, Long> {

    Optional<CustomerChangeRequest> findByCustomerIdAndStatus(long customerId, CustomerChangeStatus status);

    List<CustomerChangeRequest> findByStatusOrderByRequestedAtAscIdAsc(CustomerChangeStatus status);

    long countByStatus(CustomerChangeStatus status);

    List<CustomerChangeRequest> findByCustomerIdOrderByRequestedAtAscIdAsc(long customerId);

    @Modifying
    @Query("delete from CustomerChangeRequest r where r.customerId = :customerId")
    int deleteByCustomer(@Param("customerId") long customerId);
}
