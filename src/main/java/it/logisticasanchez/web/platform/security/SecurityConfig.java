package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.SecurityProperties;
import it.logisticasanchez.web.platform.config.SiteProperties;
import it.logisticasanchez.web.platform.security.SecurityRoutes.Rule;
import it.logisticasanchez.web.platform.web.RequestIdFilter;
import it.logisticasanchez.web.platform.web.error.ErrorResponder;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The security chain (chapter 10 §4, ADR 0002): deny by default, the declared routes of the
 * {@link RouteRegistry} as the explicit permit list, method security for the services, CSRF on
 * every customer-area and back-office form (session repository; HTMX sends the token in the
 * {@code X-CSRF-TOKEN} header from the meta tag), a new session at login, idle and absolute
 * timeouts, no saved request (public pages stay cookie-free), the error model for every
 * refusal. Response headers are owned by
 * {@link SecurityHeadersFilter}, so Spring Security's header writers are off. The login
 * mechanisms (staff form login, logouts, session registry) are added by the {@code auth}
 * module through {@link SecurityChainContributor}; the customer one-time-token sign-in is a
 * controller of that module.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RouteRegistry routes,
            ErrorResponder responder,
            SecurityProperties security,
            Clock clock,
            List<SecurityChainContributor> contributors)
            throws Exception {
        HttpSessionCsrfTokenRepository csrfRepository = new HttpSessionCsrfTokenRepository();
        RequestMatcher[] csrfExempt =
                routes.csrfExempt().stream().map(RouteRegistry.Entry::matcher).toArray(RequestMatcher[]::new);
        http.authorizeHttpRequests(requests -> {
                    requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    for (RouteRegistry.Entry entry : routes.entries()) {
                        authorize(requests.requestMatchers(entry.matcher()), entry.rule());
                    }
                    requests.anyRequest().denyAll();
                })
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).ignoringRequestMatchers(csrfExempt))
                .headers(headers -> headers.disable())
                .sessionManagement(sessions -> sessions.sessionFixation().newSession())
                // No saved request: it would create a session (a cookie) on every refused
                // anonymous request, and public pages stay cookie-free (chapter 10 §2). After
                // sign-in the user lands on the area's home page.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                                new PlatformAuthenticationEntryPoint(routes, responder))
                        .accessDeniedHandler(new PlatformAccessDeniedHandler(routes, responder)))
                .addFilterAfter(new SessionLimitsFilter(security, clock), SecurityContextHolderFilter.class)
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        // The auth module adds the staff form login, the logouts and the session registry here.
        for (SecurityChainContributor contributor : contributors) {
            contributor.contribute(http);
        }
        return http.build();
    }

    private static void authorize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizedUrl url, Rule rule) {
        switch (rule.access()) {
            case PUBLIC -> url.permitAll();
            case CUSTOMER -> url.hasRole(Authorities.CUSTOMER);
            case STAFF -> url.hasAnyRole(Authorities.ADMIN, Authorities.OPERATOR);
            case ADMIN -> url.hasRole(Authorities.ADMIN);
        }
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(PlatformFilterOrder.REQUEST_ID);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ForwardedHeadersFilter> forwardedHeadersFilter(SecurityProperties security) {
        FilterRegistrationBean<ForwardedHeadersFilter> registration = new FilterRegistrationBean<>(
                new ForwardedHeadersFilter(security.proxy().trustForwardedHeaders()));
        registration.setOrder(PlatformFilterOrder.FORWARDED_HEADERS);
        return registration;
    }

    @Bean
    FilterRegistrationBean<HostRoutingFilter> hostRoutingFilter(SiteProperties site, BusinessProperties business) {
        FilterRegistrationBean<HostRoutingFilter> registration =
                new FilterRegistrationBean<>(new HostRoutingFilter(site, business));
        registration.setOrder(PlatformFilterOrder.HOST_ROUTING);
        return registration;
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter(
            RouteRegistry routes, SiteProperties site, SecurityProperties security) {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter(routes, site, security));
        registration.setOrder(PlatformFilterOrder.SECURITY_HEADERS);
        return registration;
    }

    @Bean
    FilterRegistrationBean<PublicEtagFilter> publicEtagFilter(RouteRegistry routes) {
        FilterRegistrationBean<PublicEtagFilter> registration =
                new FilterRegistrationBean<>(new PublicEtagFilter(routes));
        registration.setOrder(PlatformFilterOrder.ETAG);
        return registration;
    }
}
