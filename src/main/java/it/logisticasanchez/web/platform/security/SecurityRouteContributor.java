package it.logisticasanchez.web.platform.security;

/**
 * The one extension point a module implements to put its routes in the security chain (chapter
 * 10 §8, ADR 0002). Every mapped route must be declared here with its access, or it does not
 * exist: an undeclared path is refused (404) for everyone. Declared in Phase 1 by
 * {@code platform} (URL-map baseline) and {@code auth}; each Phase 2 module adds one bean.
 */
public interface SecurityRouteContributor {

    void contribute(SecurityRoutes routes);
}
