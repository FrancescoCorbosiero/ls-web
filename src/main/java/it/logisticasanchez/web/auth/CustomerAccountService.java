package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.notifications.NotificationService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the {@code customers} module may do with login identities without touching the auth
 * repositories (chapter 10 §2 "modules talk through services"): look an account up, check that a
 * {@code REGISTER} link belongs to an e-mail (the token stores only its HMAC), create the OWNER
 * login of a customer who just registered through that link (the consumed token proves the
 * mailbox, exactly like a sign-in link), send a sign-in link to a known address, and the CUS-06
 * e-mail change: the new address gets a
 * {@code CHANGE_EMAIL} link (EML-01), the old one a notice, and only the CSRF-protected
 * {@code POST} of the link page applies the change and ends every other session.
 */
@Service
public class CustomerAccountService {

    private final CustomerUserRepository users;
    private final LoginTokenService tokens;
    private final CustomerSignInService signIn;
    private final CustomerSessionService sessions;
    private final EmailHasher emailHasher;
    private final NotificationService notifications;
    private final AuthMails mails;
    private final ObjectProvider<CustomerEmailChangedListener> listeners;

    public CustomerAccountService(
            CustomerUserRepository users,
            LoginTokenService tokens,
            CustomerSignInService signIn,
            CustomerSessionService sessions,
            EmailHasher emailHasher,
            NotificationService notifications,
            AuthMails mails,
            ObjectProvider<CustomerEmailChangedListener> listeners) {
        this.users = users;
        this.tokens = tokens;
        this.signIn = signIn;
        this.sessions = sessions;
        this.emailHasher = emailHasher;
        this.notifications = notifications;
        this.mails = mails;
        this.listeners = listeners;
    }

    /**
     * Erasure (NFR-03, ADR 0024): every login identity of the customer gets the placeholder
     * address and is disabled, its one-time links are deleted (the outbox rows that carried them
     * were detached first) and its live sessions end. Returns the addresses that were replaced.
     */
    @Transactional
    public List<String> anonymise(long customerId, String placeholderEmail) {
        List<String> replaced = new ArrayList<>();
        Instant now = Instant.now();
        for (CustomerUser user : users.findByCustomerId(customerId)) {
            replaced.add(user.getEmail());
            tokens.deleteAll(user.getId(), emailHasher.hash(user.getEmail()));
            if (user.getPendingEmail() != null) {
                tokens.deleteAll(user.getId(), emailHasher.hash(user.getPendingEmail()));
            }
            user.setEmail(placeholderEmail);
            user.setPendingEmail(null);
            user.clearPassword();
            if (user.getDisabledAt() == null) {
                user.setDisabledAt(now);
            }
            sessions.endAllSessions(user.getId());
        }
        return replaced;
    }

    /** A login identity as other modules see it. */
    public record Account(long customerUserId, long customerId, String email, String pendingEmail, boolean disabled) {}

    @Transactional(readOnly = true)
    public Optional<Account> findByEmail(String email) {
        return users.findByEmail(CustomerSignInService.normalise(email)).map(CustomerAccountService::toAccount);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(long customerUserId) {
        return users.findById(customerUserId).map(CustomerAccountService::toAccount);
    }

    /** True when the normalised address is the login or the pending address of any identity. */
    @Transactional(readOnly = true)
    public boolean isEmailInUse(String email) {
        String normalised = CustomerSignInService.normalise(email);
        return users.findByEmail(normalised).isPresent() || users.existsByPendingEmail(normalised);
    }

    public static String normalise(String email) {
        return CustomerSignInService.normalise(email);
    }

    /** True when the raw token is a usable {@code REGISTER} link (nothing consumed). */
    @Transactional(readOnly = true)
    public boolean isUsableRegistrationToken(String rawToken) {
        return signIn.isUsableRegistrationToken(rawToken);
    }

    /** True when the raw token is a usable {@code REGISTER} link issued for exactly this (normalised) e-mail. */
    @Transactional(readOnly = true)
    public boolean registrationTokenMatches(String rawToken, String email) {
        return tokens.peek(rawToken, LoginTokenPurpose.REGISTER)
                .map(token -> token.getEmailHash().equals(emailHasher.hash(CustomerSignInService.normalise(email))))
                .orElse(false);
    }

    /** Sends the sign-in link of a known address (the "existing e-mail registers again" case of CUS-02). */
    @Transactional
    public void sendSignInLink(String email, String clientIp) {
        signIn.issue(CustomerSignInService.normalise(email), clientIp);
    }

    /**
     * Consumes the {@code REGISTER} token of {@code email} and creates the OWNER login of the new
     * customer (M1: one per customer, Q-11), signed in at {@code now}; the principal goes into the
     * session exactly like after a sign-in link. Fails when the token is not usable for that e-mail.
     */
    @Transactional
    public CustomerPrincipal registerVerified(String rawToken, String email, long customerId, Instant now) {
        String normalised = CustomerSignInService.normalise(email);
        LoginToken token = tokens.consume(rawToken, LoginTokenPurpose.REGISTER);
        if (!token.getEmailHash().equals(emailHasher.hash(normalised))) {
            throw new LoginTokenService.InvalidTokenException();
        }
        CustomerUser user = users.save(new CustomerUser(customerId, normalised, CustomerUserRole.OWNER));
        user.setLastLoginAt(now);
        return new CustomerPrincipal(user.getId(), user.getCustomerId(), user.getEmail());
    }

    /**
     * ADM-12: the OWNER login of a customer created by staff in the back office. No link is
     * consumed here: the customer proves the mailbox with its first sign-in link (CUS-01), which
     * also marks the e-mail verified. The caller has checked that the address is free.
     */
    @Transactional
    public long createOwnerLogin(long customerId, String email) {
        CustomerUser user = users.save(
                new CustomerUser(customerId, CustomerSignInService.normalise(email), CustomerUserRole.OWNER));
        return user.getId();
    }

    /**
     * CUS-06 step 1: stores the new address as pending, queues the confirmation link to it and a
     * notice to the current address. Returns false (nothing queued) when the address is already
     * someone's login: the page answers the same way either way.
     */
    @Transactional
    public boolean requestEmailChange(long customerUserId, String newEmail, String clientIp) {
        String normalised = CustomerSignInService.normalise(newEmail);
        CustomerUser user = users.findById(customerUserId).orElseThrow();
        if (user.isDisabled()
                || normalised.equals(user.getEmail())
                || users.findByEmail(normalised).isPresent()) {
            return false;
        }
        user.setPendingEmail(normalised);
        LoginTokenService.IssuedToken issued = tokens.issue(LoginTokenPurpose.CHANGE_EMAIL, user.getId(), clientIp);
        notifications.enqueue(mails.changeEmail(normalised, tokens.validity(LoginTokenPurpose.CHANGE_EMAIL))
                .withTokenLink(issued.token().getId(), issued.rawToken()));
        notifications.enqueue(mails.changeEmailNotice(user.getEmail(), normalised));
        return true;
    }

    /** The pending change behind a usable {@code CHANGE_EMAIL} token (the {@code GET}; nothing consumed). */
    @Transactional(readOnly = true)
    public Optional<EmailChange> peekEmailChange(String rawToken) {
        return tokens.peek(rawToken, LoginTokenPurpose.CHANGE_EMAIL)
                .flatMap(token -> users.findById(token.getCustomerUserId()))
                .filter(user -> user.getPendingEmail() != null && !user.isDisabled())
                .map(user ->
                        new EmailChange(user.getId(), user.getCustomerId(), user.getEmail(), user.getPendingEmail()));
    }

    /**
     * CUS-06 step 2 (the {@code POST}): consumes the token, applies the pending address to the
     * login, tells the {@code customers} module, ends every session of the user.
     */
    @Transactional
    public EmailChange confirmEmailChange(String rawToken) {
        LoginToken token = tokens.consume(rawToken, LoginTokenPurpose.CHANGE_EMAIL);
        CustomerUser user = users.findById(token.getCustomerUserId())
                .filter(u -> u.getPendingEmail() != null && !u.isDisabled())
                .orElseThrow(LoginTokenService.InvalidTokenException::new);
        String newEmail = user.getPendingEmail();
        if (users.findByEmail(newEmail).isPresent()) {
            // taken meanwhile: the link cannot be honoured
            user.setPendingEmail(null);
            throw new LoginTokenService.InvalidTokenException();
        }
        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setPendingEmail(null);
        users.flush();
        List<CustomerEmailChangedListener> hooks = listeners.orderedStream().toList();
        for (CustomerEmailChangedListener hook : hooks) {
            hook.onEmailChanged(user.getId(), user.getCustomerId(), oldEmail, newEmail);
        }
        sessions.endAllSessions(user.getId());
        return new EmailChange(user.getId(), user.getCustomerId(), oldEmail, newEmail);
    }

    /** A requested change of login e-mail. */
    public record EmailChange(long customerUserId, long customerId, String oldEmail, String newEmail) {}

    private static Account toAccount(CustomerUser user) {
        return new Account(
                user.getId(), user.getCustomerId(), user.getEmail(), user.getPendingEmail(), user.isDisabled());
    }
}
