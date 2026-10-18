package it.logisticasanchez.web.support;

import it.logisticasanchez.web.platform.security.SecurityRouteContributor;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import org.springframework.http.HttpMethod;

/** The routes of {@link FixtureController}, declared the way a module declares its own. */
public class FixtureRoutes implements SecurityRouteContributor {

    @Override
    public void contribute(SecurityRoutes routes) {
        routes.permitGet("/fixture/public", "/fixture/forwarded", "/fixture/ratelimit", "/fixture/errors/**")
                .permit(HttpMethod.POST, "/fixture/errors/**", "/fixture/form")
                .customer(HttpMethod.GET, "/area-clienti/fixture")
                .customer(HttpMethod.POST, "/area-clienti/fixture")
                .staff(HttpMethod.GET, "/gestione/fixture")
                .staff(HttpMethod.POST, "/gestione/fixture")
                .admin(HttpMethod.GET, "/gestione/fixture/admin");
    }
}
