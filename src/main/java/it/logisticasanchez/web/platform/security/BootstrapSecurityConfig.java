package it.logisticasanchez.web.platform.security;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Deny-by-default security chain of the skeleton (stage 1).
 *
 * <p>Only the health endpoint is reachable (NFR-04: the single management endpoint on the
 * public port, without details); every other request is refused with 401 until the real chain
 * of stage 5 (chapter 10 §4: real request paths, one registration point per module) replaces
 * this class. No user store exists yet, so Spring Boot's generated in-memory user is disabled
 * ({@code spring.security.user} is never used; see application.yml).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class BootstrapSecurityConfig {

    @Bean
    SecurityFilterChain bootstrapSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
