package it.logisticasanchez.web.customers;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    List<Customer> findByStatusOrderByRegisteredAtAsc(CustomerStatus status);

    List<Customer> findByStatusInOrderByRegisteredAtAsc(Collection<CustomerStatus> statuses);

    /** The back-office list (ADM-03): live customers, newest first, optionally matching a text on name, e-mail or city. */
    @Query("select c from Customer c where c.anonymisedAt is null"
            + " and (:like = '' or lower(c.companyName) like :like or lower(c.email) like :like or lower(c.city) like :like)"
            + " order by c.registeredAt desc, c.id desc")
    Page<Customer> search(@Param("like") String like, Pageable page);

    List<Customer> findByAnonymisedAtIsNullOrderByCompanyNameAscIdAsc();
}
