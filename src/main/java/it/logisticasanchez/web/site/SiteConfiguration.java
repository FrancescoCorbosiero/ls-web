package it.logisticasanchez.web.site;

import it.logisticasanchez.web.platform.security.PlatformFilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Servlet filters of the {@code site} module: the redirect table, right after host routing. */
@Configuration(proxyBeanMethods = false)
public class SiteConfiguration {

    @Bean
    FilterRegistrationBean<SiteRedirectFilter> siteRedirectFilter() {
        FilterRegistrationBean<SiteRedirectFilter> registration =
                new FilterRegistrationBean<>(new SiteRedirectFilter());
        registration.setOrder(PlatformFilterOrder.HOST_ROUTING + 5);
        return registration;
    }
}
