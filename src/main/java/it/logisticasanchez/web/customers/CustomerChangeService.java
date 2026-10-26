package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.events.DomainEvent;
import it.logisticasanchez.web.platform.events.DomainEvents;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's own data changes (ENH-16, ADR 0038). An ACTIVE customer saves the registration
 * fields from its profile on the version the page showed: the address and the phone change at
 * once (the next orders' sender, Q-13); a different company name, VAT number or tax code becomes
 * the customer's one pending request, which a new save replaces and which goes away when the
 * values asked for are the present ones again (or the customer takes it back). The office
 * approves it — the values are copied to the customer — or declines it, with an optional reason;
 * the customer gets EML-12 either way. Every save that changes something tells the office
 * (EML-06); every move is audited on the customer with the values in the personal detail, so the
 * erasure (NFR-03) removes them. The login e-mail stays CUS-06. Flags are checked by the callers
 * (ADR 0025): this service is the data, not the feature switch.
 */
@Service
public class CustomerChangeService {

    private final CustomerRepository customers;
    private final CustomerChangeRequestRepository requests;
    private final CustomerMails mails;
    private final NotificationService notifications;
    private final AuditService audit;
    private final DomainEvents events;
    private final Clock clock;

    public CustomerChangeService(
            CustomerRepository customers,
            CustomerChangeRequestRepository requests,
            CustomerMails mails,
            NotificationService notifications,
            AuditService audit,
            DomainEvents events,
            Clock clock) {
        this.customers = customers;
        this.requests = requests;
        this.mails = mails;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** What a save of the profile did: the address or phone changed, a change of identity was asked for, a pending one taken back. */
    public record Saved(boolean applied, boolean requested, boolean withdrawn) {

        public boolean nothing() {
            return !applied && !requested && !withdrawn;
        }
    }

    /** The customer's pending request, with its present values beside it. */
    @Transactional(readOnly = true)
    public Optional<ChangeRequestView> pending(long customerId) {
        return requests.findByCustomerIdAndStatus(customerId, CustomerChangeStatus.PENDING)
                .map(r -> view(r, customers.findById(customerId).orElseThrow()));
    }

    /** Every pending request, oldest first («In Approvazione»). */
    @Transactional(readOnly = true)
    public List<ChangeRequestView> allPending() {
        List<CustomerChangeRequest> pending =
                requests.findByStatusOrderByRequestedAtAscIdAsc(CustomerChangeStatus.PENDING);
        Map<Long, Customer> owners =
                customers
                        .findAllById(pending.stream()
                                .map(CustomerChangeRequest::getCustomerId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(Customer::getId, Function.identity()));
        return pending.stream().map(r -> view(r, owners.get(r.getCustomerId()))).toList();
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return requests.countByStatus(CustomerChangeStatus.PENDING);
    }

    /**
     * Saves the profile of an ACTIVE customer on {@code expectedVersion} (the page's). Refused,
     * with nothing changed, for another state or when the customer changed meanwhile (a save on
     * another device, an approval, a correction by the office).
     */
    @Transactional
    public Saved save(long customerId, long customerUserId, int expectedVersion, RegistrationData data) {
        Customer customer = customers.lockById(customerId).orElseThrow();
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new RuleViolationException("Puoi modificare i tuoi dati quando il tuo account è attivo.");
        }
        if (customer.getVersion() != expectedVersion) {
            throw new RuleViolationException(
                    "I tuoi dati sono stati modificati nel frattempo: controlla i dati aggiornati e riprova.");
        }
        Map<String, String> before = contacts(customer);
        boolean applied = customer.changeContacts(data);
        List<ChangeRequestView.Field> appliedFields = new ArrayList<>();
        if (applied) {
            Map<String, String> after = contacts(customer);
            before.forEach((field, value) -> {
                if (!Objects.equals(value, after.get(field))) {
                    appliedFields.add(new ChangeRequestView.Field(field, value, after.get(field)));
                }
            });
            audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "customer.update")
                    .entity("customer", customer.getId(), null)
                    .personal(before, after));
        }

        Instant now = clock.instant();
        CustomerIdentity wanted = CustomerIdentity.of(data);
        CustomerIdentity present = CustomerIdentity.of(customer);
        Optional<CustomerChangeRequest> pending =
                requests.findByCustomerIdAndStatus(customerId, CustomerChangeStatus.PENDING);
        boolean requested = false;
        boolean withdrawn = false;
        CustomerChangeRequest request = null;
        if (wanted.equals(present)) {
            if (pending.isPresent()) {
                withdraw(pending.get(), customer, customerUserId, now);
                withdrawn = true;
            }
        } else if (pending.isEmpty() || !pending.get().requested().equals(wanted)) {
            CustomerIdentity previous =
                    pending.map(CustomerChangeRequest::requested).orElse(present);
            if (pending.isPresent()) {
                request = pending.get();
                request.replace(wanted, now);
            } else {
                request = requests.save(new CustomerChangeRequest(customerId, wanted, now));
            }
            audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "customer.change.request")
                    .entity("customer", customer.getId(), null)
                    .change(null, Map.of("request", request.getId()))
                    .personal(previous.values(), wanted.values()));
            requested = true;
        }
        if (applied || requested) {
            notifications.enqueue(
                    mails.profileChanged(customer, appliedFields, requested ? view(request, customer) : null));
        }
        return new Saved(applied, requested, withdrawn);
    }

    /** The customer takes its pending request back; false when there was none. */
    @Transactional
    public boolean withdraw(long customerId, long customerUserId) {
        Customer customer = customers.lockById(customerId).orElseThrow();
        Optional<CustomerChangeRequest> pending =
                requests.findByCustomerIdAndStatus(customerId, CustomerChangeStatus.PENDING);
        pending.ifPresent(r -> withdraw(r, customer, customerUserId, clock.instant()));
        return pending.isPresent();
    }

    private void withdraw(CustomerChangeRequest request, Customer customer, long customerUserId, Instant now) {
        request.withdraw(now);
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "customer.change.withdraw")
                .entity("customer", customer.getId(), null)
                .change(
                        Map.of("status", "PENDING"),
                        Map.of("status", request.getStatus().name(), "request", request.getId())));
    }

    /** The office approves the request: the values asked for become the customer's; EML-12 to the customer. */
    @Transactional
    public CustomerView approve(long customerId, long requestId, long staffUserId) {
        Customer customer = customers.lockById(customerId).orElseThrow();
        CustomerChangeRequest request = pendingRequest(customerId, requestId);
        CustomerIdentity before = CustomerIdentity.of(customer);
        customer.changeIdentity(request.requested());
        request.approve(staffUserId, clock.instant());
        notifications.enqueue(mails.changeApproved(customer));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "customer.change.approve")
                .entity("customer", customer.getId(), null)
                .change(
                        Map.of("status", "PENDING"),
                        Map.of("status", request.getStatus().name(), "request", requestId))
                .personal(before.values(), CustomerIdentity.of(customer).values()));
        events.publish(new DomainEvent.CustomerChanged(customer.getId(), staffUserId));
        return CustomerView.of(customer);
    }

    /** The office declines the request, with an optional reason for the customer (EML-12); the customer's data stay. */
    @Transactional
    public void decline(long customerId, long requestId, long staffUserId, String reason) {
        Customer customer = customers.lockById(customerId).orElseThrow();
        CustomerChangeRequest request = pendingRequest(customerId, requestId);
        request.decline(staffUserId, clock.instant(), reason);
        notifications.enqueue(mails.changeDeclined(customer, request.getDeclineReason()));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "customer.change.decline")
                .entity("customer", customer.getId(), null)
                .change(
                        Map.of("status", "PENDING"),
                        Map.of(
                                "status",
                                request.getStatus().name(),
                                "request",
                                requestId,
                                "reason",
                                request.getDeclineReason() != null)));
    }

    private CustomerChangeRequest pendingRequest(long customerId, long requestId) {
        return requests.findById(requestId)
                .filter(r -> r.getCustomerId() == customerId && r.getStatus() == CustomerChangeStatus.PENDING)
                .orElseThrow(() -> new RuleViolationException("La richiesta di modifica non è più in attesa."));
    }

    /** NFR-03 export: every request of the customer, oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportFor(long customerId) {
        return requests.findByCustomerIdOrderByRequestedAtAscIdAsc(customerId).stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>(r.requested().values());
                    row.put("stato", r.getStatus().name());
                    row.put("richiestaIl", r.getRequestedAt());
                    row.put("chiusaIl", r.getClosedAt());
                    row.put("motivoRifiuto", r.getDeclineReason());
                    return row;
                })
                .toList();
    }

    /** NFR-03 erasure: deletes the customer's requests; returns how many. */
    @Transactional
    public int erase(long customerId) {
        return requests.deleteByCustomer(customerId);
    }

    private static ChangeRequestView view(CustomerChangeRequest r, Customer owner) {
        return new ChangeRequestView(
                r.getId(),
                r.getCustomerId(),
                owner.getEmail(),
                r.getStatus(),
                r.requested(),
                CustomerIdentity.of(owner),
                r.getRequestedAt(),
                r.getClosedAt(),
                r.getDeclineReason());
    }

    /** The fields a customer changes at once, in the form's order. */
    private static Map<String, String> contacts(Customer c) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("addressLine", c.getAddressLine());
        values.put("province", c.getProvince());
        values.put("city", c.getCity());
        values.put("postalCode", c.getPostalCode());
        values.put("phone", c.getPhone());
        return values;
    }
}
