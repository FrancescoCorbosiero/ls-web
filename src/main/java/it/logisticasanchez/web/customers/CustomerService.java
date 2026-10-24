package it.logisticasanchez.web.customers;

import it.logisticasanchez.web.auth.CustomerAccountService;
import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.auth.LoginTokenService;
import it.logisticasanchez.web.notifications.NotificationService;
import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.events.DomainEvent;
import it.logisticasanchez.web.platform.events.DomainEvents;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customers module's public service (chapter 10 §2; plan §4.1): registration from a
 * {@code REGISTER} link (CUS-02), re-registration of a declined customer (Q-09), the state
 * machine of chapter 08 §2.4 for the back office (approve only a verified customer — Q-28 —,
 * decline), the e-mail verification hook of the sign-in, the profile data and the e-mail change
 * (CUS-06, through the auth module's {@link CustomerAccountService}). Every transition writes
 * the audit log and queues its e-mails in the same transaction; events carry ids only.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final CustomerAccountService accounts;
    private final CustomerSessionService sessions;
    private final CustomerMails mails;
    private final NotificationService notifications;
    private final AuditService audit;
    private final DomainEvents events;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customers,
            CustomerAccountService accounts,
            CustomerSessionService sessions,
            CustomerMails mails,
            NotificationService notifications,
            AuditService audit,
            DomainEvents events,
            Clock clock) {
        this.customers = customers;
        this.accounts = accounts;
        this.sessions = sessions;
        this.mails = mails;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<CustomerView> find(long customerId) {
        return customers.findById(customerId).map(CustomerView::of);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerView> findByEmail(String email) {
        return customers.findByEmail(CustomerAccountService.normalise(email)).map(CustomerView::of);
    }

    /** The customers waiting for approval, oldest first (ADM-02). */
    @Transactional(readOnly = true)
    public List<CustomerView> pendingApproval() {
        return customers.findByStatusOrderByRegisteredAtAsc(CustomerStatus.PENDING_APPROVAL).stream()
                .map(CustomerView::of)
                .toList();
    }

    /** True when the raw token is a usable {@code REGISTER} link (the form's {@code GET}; nothing consumed). */
    @Transactional(readOnly = true)
    public boolean isUsableRegistrationLink(String rawToken) {
        return accounts.isUsableRegistrationToken(rawToken);
    }

    /** True when the usable {@code REGISTER} link was issued for this (normalised) e-mail; nothing consumed. */
    @Transactional(readOnly = true)
    public boolean registrationLinkMatches(String rawToken, String email) {
        return accounts.registrationTokenMatches(rawToken, CustomerAccountService.normalise(email));
    }

    /**
     * CUS-02 from a {@code REGISTER} link: {@code email} must be the address the link was sent
     * to (the token stores only its HMAC). An address that is already a login creates nothing
     * and gets a sign-in link ({@link Outcome#SIGN_IN_LINK_SENT}); a link that is used, expired
     * or for another address answers {@link Outcome#LINK_INVALID}. Otherwise the customer is
     * created {@code PENDING_APPROVAL} (the link proved the mailbox) with its OWNER login, the
     * token is consumed, EML-02 goes to the customer and EML-06 to the office, and the principal
     * to sign in is returned.
     */
    @Transactional
    public Registration register(String rawToken, String email, RegistrationData data, String clientIp) {
        String normalised = CustomerAccountService.normalise(email);
        if (!accounts.registrationTokenMatches(rawToken, normalised)) {
            return new Registration(Outcome.LINK_INVALID, null);
        }
        if (accounts.isEmailInUse(normalised)
                || customers.findByEmail(normalised).isPresent()) {
            accounts.sendSignInLink(normalised, clientIp);
            return new Registration(Outcome.SIGN_IN_LINK_SENT, null);
        }
        Instant now = clock.instant();
        Customer customer = customers.save(new Customer(normalised, data, now, now));
        CustomerPrincipal principal;
        try {
            principal = accounts.registerVerified(rawToken, normalised, customer.getId(), now);
        } catch (LoginTokenService.InvalidTokenException e) {
            throw new RuleViolationException("Il link non è più valido.");
        }
        notifications.enqueue(mails.registrationReceived(customer));
        notifications.enqueue(mails.newRegistration(customer));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, principal.customerUserId(), "customer.register")
                .entity("customer", customer.getId(), null)
                .change(null, Map.of("status", customer.getStatus().name(), "verified", true))
                .personal(null, personal(customer)));
        events.publish(new DomainEvent.CustomerVerified(customer.getId()));
        return new Registration(Outcome.REGISTERED, principal);
    }

    /** Q-09: a signed-in DECLINED customer sends new data and goes back to PENDING_APPROVAL (the sign-in proved the mailbox). */
    @Transactional
    public void registerAgain(long customerId, long customerUserId, RegistrationData data) {
        Customer customer = customers.findById(customerId).orElseThrow();
        Map<String, Object> before = personal(customer);
        String previous = customer.getStatus().name();
        customer.registerAgain(data, clock.instant());
        notifications.enqueue(mails.registrationReceived(customer));
        notifications.enqueue(mails.newRegistration(customer));
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "customer.reregister")
                .entity("customer", customer.getId(), null)
                .change(
                        Map.of("status", previous),
                        Map.of("status", customer.getStatus().name()))
                .personal(before, personal(customer)));
        events.publish(new DomainEvent.CustomerVerified(customer.getId()));
    }

    /** ADM-02 approve → ACTIVE + EML-03; refused unless PENDING_APPROVAL with a verified e-mail. */
    @Transactional
    public CustomerView approve(long customerId, long staffUserId) {
        Customer customer = customers.findById(customerId).orElseThrow();
        String previous = customer.getStatus().name();
        customer.approve(staffUserId, clock.instant());
        notifications.enqueue(mails.approved(customer));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "customer.approve")
                .entity("customer", customer.getId(), null)
                .change(
                        Map.of("status", previous),
                        Map.of("status", customer.getStatus().name())));
        events.publish(new DomainEvent.CustomerApproved(customer.getId(), staffUserId));
        return CustomerView.of(customer);
    }

    /** ADM-02 decline → DECLINED + EML-03 (decline text); ends the customer's sessions. */
    @Transactional
    public CustomerView decline(long customerId, long staffUserId, String reason) {
        Customer customer = customers.findById(customerId).orElseThrow();
        String previous = customer.getStatus().name();
        customer.decline(staffUserId, clock.instant(), reason);
        notifications.enqueue(mails.declined(customer));
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "customer.decline")
                .entity("customer", customer.getId(), null)
                .change(
                        Map.of("status", previous),
                        Map.of("status", customer.getStatus().name())));
        events.publish(new DomainEvent.CustomerDeclined(customer.getId(), staffUserId));
        accounts.findByEmail(customer.getEmail()).ifPresent(a -> sessions.endAllSessions(a.customerUserId()));
        return CustomerView.of(customer);
    }

    /** "Sign-in link used" (chapter 08 §2.4): marks the e-mail verified; PENDING_VERIFICATION moves on with EML-02 + EML-06. */
    @Transactional
    public void emailVerified(long customerId, Instant at) {
        Customer customer = customers.findById(customerId).orElseThrow();
        if (customer.verifyEmail(at)) {
            notifications.enqueue(mails.registrationReceived(customer));
            notifications.enqueue(mails.newRegistration(customer));
            audit.record(AuditEntry.system("customer.email.verified")
                    .entity("customer", customer.getId(), null)
                    .change(
                            Map.of("status", CustomerStatus.PENDING_VERIFICATION.name()),
                            Map.of("status", customer.getStatus().name())));
            events.publish(new DomainEvent.CustomerVerified(customer.getId()));
        }
    }

    /** CUS-06: the login e-mail of a customer changed (called inside the auth module's transaction). */
    @Transactional
    public void emailChanged(long customerId, long customerUserId, String oldEmail, String newEmail) {
        Customer customer = customers.findById(customerId).orElseThrow();
        customer.changeEmail(newEmail);
        audit.record(AuditEntry.of(AuditActorType.CUSTOMER, customerUserId, "customer.email.changed")
                .entity("customer", customer.getId(), null)
                .personal(Map.of("email", oldEmail), Map.of("email", newEmail)));
    }

    /** CUS-06 step 1, from the signed-in profile page: a link to the new address, a notice to the old one. */
    public boolean requestEmailChange(long customerUserId, String newEmail, String clientIp) {
        return accounts.requestEmailChange(customerUserId, newEmail, clientIp);
    }

    private static Map<String, Object> personal(Customer c) {
        return Map.of(
                "email", c.getEmail(),
                "companyName", c.getCompanyName(),
                "addressLine", c.getAddressLine(),
                "city", c.getCity(),
                "province", c.getProvince(),
                "postalCode", c.getPostalCode(),
                "phone", c.getPhone());
    }

    public enum Outcome {
        REGISTERED,
        SIGN_IN_LINK_SENT,
        LINK_INVALID
    }

    /** What a registration produced; {@code principal} only for {@link Outcome#REGISTERED}. */
    public record Registration(Outcome outcome, CustomerPrincipal principal) {}
}
