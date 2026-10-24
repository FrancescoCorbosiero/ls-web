package it.logisticasanchez.web.customers;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    List<Customer> findByStatusOrderByRegisteredAtAsc(CustomerStatus status);
}
