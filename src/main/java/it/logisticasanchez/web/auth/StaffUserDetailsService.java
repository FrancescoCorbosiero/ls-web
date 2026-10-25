package it.logisticasanchez.web.auth;

import java.time.Clock;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads staff accounts for the form login (ADM-01); usernames are matched trimmed and lower-case. */
@Service
public class StaffUserDetailsService implements UserDetailsService {

    private final StaffUserRepository users;
    private final Clock clock;

    public StaffUserDetailsService(StaffUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("blank username");
        }
        return users.findByUsername(StaffUser.normaliseUsername(username))
                .map(user -> new StaffPrincipal(user, clock.instant()))
                .orElseThrow(() -> new UsernameNotFoundException("unknown staff user"));
    }
}
