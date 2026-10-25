package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.config.CompatProperties;
import it.logisticasanchez.web.platform.security.PlatformFilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Servlet filters of the {@code tracking} module: CORS of the compatibility API, after the security headers. */
@Configuration(proxyBeanMethods = false)
public class TrackingConfiguration {

    @Bean
    FilterRegistrationBean<CompatCorsFilter> compatCorsFilter(CompatProperties compat) {
        FilterRegistrationBean<CompatCorsFilter> registration =
                new FilterRegistrationBean<>(new CompatCorsFilter(compat));
        registration.setOrder(PlatformFilterOrder.SECURITY_HEADERS + 5);
        return registration;
    }
}
