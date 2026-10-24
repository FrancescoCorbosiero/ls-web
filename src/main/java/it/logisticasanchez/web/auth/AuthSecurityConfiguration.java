package it.logisticasanchez.web.auth;

import java.util.List;
import java.util.Map;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * The beans behind both sign-ins (ADR 0003, ADR 0004): bcrypt with cost {@value #BCRYPT_COST}
 * behind a {@link DelegatingPasswordEncoder} (hashes carry {@code {bcrypt}}, so the algorithm
 * can change later), the session registry that lets a decline or an e-mail change end every
 * session of a user, the strategy a login applies to the session (new id, registry entry,
 * fresh CSRF token) and the context repository both logins save into.
 */
@Configuration(proxyBeanMethods = false)
public class AuthSecurityConfiguration {

    static final int BCRYPT_COST = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_COST)));
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Tells the registry when the container destroys a session (logout, idle timeout, absolute limit). */
    @Bean
    ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /**
     * What happens to the session at login, for the form login and the customer link alike: the
     * old session is dropped and a new one created (no attribute migrated; chapter 10 §2 "session id
     * rotated on login"), the session is registered, the CSRF token is regenerated.
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry registry) {
        SessionFixationProtectionStrategy fixation = new SessionFixationProtectionStrategy();
        fixation.setMigrateSessionAttributes(false);
        return new CompositeSessionAuthenticationStrategy(List.of(
                fixation,
                new RegisterSessionAuthenticationStrategy(registry),
                new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));
    }
}
