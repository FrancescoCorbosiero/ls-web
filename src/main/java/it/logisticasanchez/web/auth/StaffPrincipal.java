package it.logisticasanchez.web.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The staff user as the security context carries it (ADM-01): id, username, display name and
 * role as {@code ROLE_ADMIN} / {@code ROLE_OPERATOR}. An account without a password yet is
 * disabled; a locked account stays locked until {@code locked_until}. Equality is by id, so the
 * session registry groups the sessions of one account.
 */
public final class StaffPrincipal implements UserDetails {

    private final long id;
    private final String username;
    private final String displayName;
    private final StaffRole role;
    private final String passwordHash;
    private final boolean active;
    private final Instant lockedUntil;
    private final Instant now;

    StaffPrincipal(StaffUser user, Instant now) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
        this.passwordHash = user.getPasswordHash();
        this.active = user.isActive();
        this.lockedUntil = user.getLockedUntil();
        this.now = Objects.requireNonNull(now, "now");
    }

    public long id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public StaffRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** Null until the user sets the password; the account is then disabled, so no match is ever tried. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonLocked() {
        return lockedUntil == null || !lockedUntil.isAfter(now);
    }

    @Override
    public boolean isEnabled() {
        return active && passwordHash != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StaffPrincipal that && that.id == id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "staff:" + id;
    }
}
