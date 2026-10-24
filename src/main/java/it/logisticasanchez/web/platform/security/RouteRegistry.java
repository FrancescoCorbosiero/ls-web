package it.logisticasanchez.web.platform.security;

import it.logisticasanchez.web.platform.security.SecurityRoutes.Rule;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The routes collected from every {@link SecurityRouteContributor}, in order: the platform
 * baseline first, then the modules. Feeds the security chain, the entry point (declared or
 * unknown), the CSRF exemptions and the header filter (private areas, no-store paths).
 */
@Component
public class RouteRegistry {

    private final List<Entry> entries;
    private final List<Entry> csrfExempt;
    private final List<PathPattern> noStore;
    private final PathPatternParser parser = new PathPatternParser();

    public RouteRegistry(List<SecurityRouteContributor> contributors) {
        SecurityRoutes routes = new SecurityRoutes();
        for (SecurityRouteContributor contributor : contributors) {
            contributor.contribute(routes);
        }
        List<Entry> collected = new ArrayList<>();
        for (Rule rule : routes.rules()) {
            if (rule.access() == Access.PUBLIC && isPrivateArea(rule.pattern()) && !isSignInEntry(rule.pattern())) {
                throw new IllegalStateException(
                        "public route inside a private area refused: " + rule.pattern() + " (ADR 0002)");
            }
            collected.add(toEntry(rule));
        }
        this.entries = List.copyOf(collected);
        this.csrfExempt = routes.csrfExempt().stream().map(this::toEntry).toList();
        this.noStore = routes.noStore().stream().map(parser::parse).toList();
    }

    private Entry toEntry(Rule rule) {
        RequestMatcher matcher = rule.method() == null
                ? PathPatternRequestMatcher.withDefaults().matcher(rule.pattern())
                : PathPatternRequestMatcher.withDefaults().matcher(rule.method(), rule.pattern());
        return new Entry(rule, matcher);
    }

    /** The declared rules in match order. */
    public List<Entry> entries() {
        return entries;
    }

    public List<Entry> csrfExempt() {
        return csrfExempt;
    }

    /** The first rule matching the request, if the route is declared. */
    public Optional<Rule> ruleFor(HttpServletRequest request) {
        for (Entry entry : entries) {
            if (entry.matcher().matches(request)) {
                return Optional.of(entry.rule());
            }
        }
        return Optional.empty();
    }

    public boolean isDeclared(HttpServletRequest request) {
        return ruleFor(request).isPresent();
    }

    public boolean isCsrfExempt(HttpServletRequest request) {
        for (Entry entry : csrfExempt) {
            if (entry.matcher().matches(request)) {
                return true;
            }
        }
        return false;
    }

    /** The customer area and the back office: sessions, CSRF, {@code no-store}, private CSP. */
    public static boolean isPrivateArea(String path) {
        return path.startsWith(SecurityRoutes.CUSTOMER_AREA) || path.startsWith(SecurityRoutes.BACKOFFICE);
    }

    /** Where an anonymous request to a private area is sent: the area's sign-in page. */
    public static String signInPathFor(String path) {
        return path.startsWith(SecurityRoutes.BACKOFFICE) ? SecurityRoutes.BACKOFFICE : SecurityRoutes.CUSTOMER_AREA;
    }

    static boolean isSignInEntry(String pattern) {
        return pattern.equals(SecurityRoutes.CUSTOMER_AREA)
                || pattern.equals(SecurityRoutes.CUSTOMER_AREA + "accesso")
                || pattern.equals(SecurityRoutes.BACKOFFICE)
                || pattern.equals(SecurityRoutes.BACKOFFICE + "login")
                || pattern.equals(SecurityRoutes.BACKOFFICE + "logout")
                || pattern.equals(SecurityRoutes.CUSTOMER_AREA + "logout")
                || pattern.equals(SecurityRoutes.CUSTOMER_AREA + "registrazione/");
    }

    /** True when the public answer may be cached (PD-02): outside the private areas and not no-store. */
    public boolean isPublicCacheable(String path) {
        if (isPrivateArea(path)) {
            return false;
        }
        org.springframework.http.server.PathContainer container =
                org.springframework.http.server.PathContainer.parsePath(path);
        for (PathPattern pattern : noStore) {
            if (pattern.matches(container)) {
                return false;
            }
        }
        return true;
    }

    /** A declared rule with its compiled matcher. */
    public record Entry(Rule rule, RequestMatcher matcher) {
        public HttpMethod method() {
            return rule.method();
        }
    }
}
