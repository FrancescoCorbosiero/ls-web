package it.logisticasanchez.web.platform.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * The second extension point of the chain (ADR 0002, ADR 0003): a module that owns an
 * authentication mechanism — in M1 only {@code auth}, with the staff form login, the logouts
 * and the session registry — adds it here. Applied after the platform baseline, so a contributor
 * may add filters and configurers but never relax the deny-by-default authorisation, which is
 * built from the {@link SecurityRouteContributor}s alone.
 */
public interface SecurityChainContributor {

    void contribute(HttpSecurity http) throws Exception;
}
