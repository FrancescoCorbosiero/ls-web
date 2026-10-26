package it.logisticasanchez.web.customers;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The address book (ENH-07); read only through {@link AddressBookService}. */
interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    List<CustomerAddress> findByCustomerId(long customerId);

    Optional<CustomerAddress> findByIdAndCustomerId(long id, long customerId);

    long countByCustomerId(long customerId);

    /** The entry with this name, street and CAP (trimmed, ignoring case): the unique index of V016. */
    @Query("select a from CustomerAddress a where a.customerId = :customerId and lower(trim(a.name)) = :name"
            + " and lower(trim(a.addressLine)) = :addressLine and a.postalCode = :postalCode")
    Optional<CustomerAddress> findEntry(
            @Param("customerId") long customerId,
            @Param("name") String name,
            @Param("addressLine") String addressLine,
            @Param("postalCode") String postalCode);

    @Modifying
    @Query("delete from CustomerAddress a where a.customerId = :customerId")
    int deleteByCustomer(@Param("customerId") long customerId);
}
