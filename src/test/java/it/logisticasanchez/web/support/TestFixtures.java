package it.logisticasanchez.web.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** {@code @Import(TestFixtures.class)} adds the fixture routes to a test context. */
@TestConfiguration(proxyBeanMethods = false)
public class TestFixtures {

    @Bean
    FixtureRoutes fixtureRoutes() {
        return new FixtureRoutes();
    }

    @Bean
    FixtureController fixtureController(
            it.logisticasanchez.web.platform.ratelimit.RateLimiter rateLimiter,
            it.logisticasanchez.web.platform.security.ClientIpResolver clientIp) {
        return new FixtureController(rateLimiter, clientIp);
    }
}
