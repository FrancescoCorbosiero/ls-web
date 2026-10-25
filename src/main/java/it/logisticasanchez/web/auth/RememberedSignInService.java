package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer stays signed in on a device (owner decision of 2026-10-25, ADR 0031): after a
 * sign-in link (or the registration) {@link #remember} stores the SHA-256 of a random token and
 * sets the token in an {@code HttpOnly}, {@code SameSite=Lax} cookie limited to the customer
 * area; {@link #restore} turns the cookie's token back into the customer when the session has
 * ended (60 minutes idle, 12 hours, a restart); {@link #forget} (logout) and {@link #forgetAll}
 * (decline, e-mail change, anonymisation) delete it. Requests without the cookie never reach the
 * database ({@link #cookieToken} is checked first). The lifetime, {@code
 * ls.security.sessions.customer.remember} (60 days; zero turns the feature off), counts from the
 * sign-in and is not extended by use. The cookie follows the session cookie's {@code Secure}
 * setting: {@value #SECURE_COOKIE} in staging and production, {@value #PLAIN_COOKIE} over plain
 * http in the local and test profiles.
 */
@Service
public class RememberedSignInService {

    static final String SECURE_COOKIE = "__Secure-LS_REMEMBER";
    static final String PLAIN_COOKIE = "LS_REMEMBER";

    /** A raw token is 43 base64url characters; anything much longer is not ours. */
    private static final int MAX_TOKEN_LENGTH = 64;

    private final RememberedSignInRepository devices;
    private final CustomerUserRepository users;
    private final Duration lifetime;
    private final boolean secure;
    private final Clock clock;

    public RememberedSignInService(
            RememberedSignInRepository devices,
            CustomerUserRepository users,
            SecurityProperties security,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            Clock clock) {
        this.devices = devices;
        this.users = users;
        this.lifetime = security.sessions().customer().remember();
        this.secure = secure;
        this.clock = clock;
    }

    /** False when the owner set the lifetime to zero: nothing is remembered or restored. */
    public boolean enabled() {
        return lifetime.isPositive();
    }

    /** The whole days «Resta connesso» offers; 0 when the feature is off (the checkbox is then hidden). */
    public long offeredDays() {
        return enabled() ? lifetime.toDays() : 0;
    }

    public String cookieName() {
        return secure ? SECURE_COOKIE : PLAIN_COOKIE;
    }

    /** Remembers this device for the customer user, replacing the token the device already had. */
    @Transactional
    public void remember(long customerUserId, HttpServletRequest request, HttpServletResponse response) {
        if (!enabled()) {
            return;
        }
        rawToken(request).ifPresent(raw -> devices.deleteByTokenHash(Tokens.hash(raw)));
        String raw = Tokens.random();
        Instant now = clock.instant();
        devices.save(new RememberedSignIn(customerUserId, Tokens.hash(raw), now, now.plus(lifetime)));
        writeCookie(response, raw, lifetime);
    }

    /** The raw token of this device's cookie, if the request carries one (no database access). */
    public Optional<String> cookieToken(HttpServletRequest request) {
        return rawToken(request);
    }

    /** The customer the token is remembered for, while it has not expired and the login is not disabled. */
    @Transactional
    public Optional<CustomerPrincipal> restore(String rawToken) {
        if (!enabled()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return devices.findByTokenHash(Tokens.hash(rawToken))
                .filter(device -> device.isValidAt(now))
                .flatMap(device -> users.findById(device.getCustomerUserId())
                        .filter(user -> !user.isDisabled())
                        .map(user -> {
                            device.markUsed(now);
                            return new CustomerPrincipal(user.getId(), user.getCustomerId(), user.getEmail());
                        }));
    }

    /** Logout: the device of this token is no longer remembered. */
    @Transactional
    public void forget(String rawToken) {
        devices.deleteByTokenHash(Tokens.hash(rawToken));
    }

    /**
     * A sign-in without «Resta connesso»: the device must not keep the cookie of an earlier
     * sign-in (perhaps another customer's on a shared computer), which would reopen that account
     * once this session ends.
     */
    @Transactional
    public void forgetDevice(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> raw = rawToken(request);
        if (raw.isPresent()) {
            devices.deleteByTokenHash(Tokens.hash(raw.get()));
            clearCookie(response);
        }
    }

    /** Every device of the login (decline, e-mail change, anonymisation); returns how many. */
    @Transactional
    public int forgetAll(long customerUserId) {
        return devices.deleteByCustomerUserId(customerUserId);
    }

    /** Tells the browser to drop the cookie (after a logout, or when its token is no longer valid). */
    public void clearCookie(HttpServletResponse response) {
        writeCookie(response, "", Duration.ZERO);
    }

    private Optional<String> rawToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            String value = cookie.getValue();
            if (cookieName().equals(cookie.getName())
                    && value != null
                    && !value.isBlank()
                    && value.length() <= MAX_TOKEN_LENGTH) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private void writeCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(cookieName(), value)
                .path(AuthRoutes.CUSTOMER_AREA)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
