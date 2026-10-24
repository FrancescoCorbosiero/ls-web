package it.logisticasanchez.web.platform.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers every typed {@code ls.*} settings record (plan §8.1 and §8.2 seams). Modules inject
 * the record they consume; nothing reads {@code Environment} for an {@code ls.*} key directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    BusinessProperties.class,
    SecurityProperties.class,
    SiteProperties.class,
    CompatProperties.class,
    CustomersProperties.class,
    OrdersProperties.class,
    TrackingProperties.class,
    BackofficeProperties.class,
    ReportsProperties.class,
    RetentionProperties.class,
    RateLimitProperties.class,
    FlagsProperties.class,
    NotificationsProperties.class
})
public class PlatformPropertiesConfiguration {

    /** The application clock; tests build services with a fixed clock instead of this bean. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
