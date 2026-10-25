package it.logisticasanchez.web.site;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.stereotype.Component;

/**
 * The public routes of the {@code site} module (ADR 0002): every page of {@link SitePages}.
 * {@code /sitemap.xml}, {@code /robots.txt}, {@code /.well-known/security.txt} and
 * {@code POST /api/form} are already declared by the platform baseline.
 */
@Component
public class SiteRouteContributor implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        for (SitePages.Page page : SitePages.all()) {
            routes.permitGet(page.path());
        }
    }
}
