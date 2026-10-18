package it.logisticasanchez.web.platform.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpMethod;

/**
 * What a {@link SecurityRouteContributor} declares: routes with their {@link Access}, the paths
 * exempt from CSRF (only {@code POST /api/form} and {@code /mono/**}, chapter 10 §4) and the
 * paths outside the private areas that must not be cached. Rules are matched in declaration
 * order, platform first; a {@code null} method means every method.
 */
public final class SecurityRoutes {

    /** Paths that may be exempt from CSRF: unauthenticated, session-free JSON/form endpoints. */
    static final Set<String> CSRF_EXEMPTABLE = Set.of("/api/form", "/mono/**");

    /** The two authenticated areas of the URL map (chapter 10 §2). */
    public static final String CUSTOMER_AREA = "/area-clienti/";

    public static final String BACKOFFICE = "/gestione/";

    private final List<Rule> rules = new ArrayList<>();
    private final List<Rule> csrfExempt = new ArrayList<>();
    private final List<String> noStore = new ArrayList<>();

    /** Public for GET and HEAD. Not allowed inside the private areas except by {@code platform}. */
    public SecurityRoutes permitGet(String... patterns) {
        add(Access.PUBLIC, HttpMethod.GET, patterns);
        add(Access.PUBLIC, HttpMethod.HEAD, patterns);
        return this;
    }

    public SecurityRoutes permit(HttpMethod method, String... patterns) {
        add(Access.PUBLIC, method, patterns);
        return this;
    }

    public SecurityRoutes customer(HttpMethod method, String... patterns) {
        add(Access.CUSTOMER, method, patterns);
        return this;
    }

    public SecurityRoutes staff(HttpMethod method, String... patterns) {
        add(Access.STAFF, method, patterns);
        return this;
    }

    public SecurityRoutes admin(HttpMethod method, String... patterns) {
        add(Access.ADMIN, method, patterns);
        return this;
    }

    /** CSRF exemption; refused for any path outside {@link #CSRF_EXEMPTABLE}. */
    public SecurityRoutes csrfExempt(HttpMethod method, String... patterns) {
        for (String pattern : patterns) {
            if (!CSRF_EXEMPTABLE.contains(pattern)) {
                throw new IllegalArgumentException("CSRF exemption refused for " + pattern + " (chapter 10 §4)");
            }
            csrfExempt.add(new Rule(Access.PUBLIC, method, pattern));
        }
        return this;
    }

    /** A public path whose answer must not be cached (sent {@code Cache-Control: no-store}). */
    public SecurityRoutes noStore(String... patterns) {
        Collections.addAll(noStore, patterns);
        return this;
    }

    private void add(Access access, HttpMethod method, String... patterns) {
        for (String pattern : patterns) {
            Objects.requireNonNull(pattern, "pattern");
            if (!pattern.startsWith("/")) {
                throw new IllegalArgumentException("pattern must start with /: " + pattern);
            }
            rules.add(new Rule(access, method, pattern));
        }
    }

    List<Rule> rules() {
        return rules;
    }

    List<Rule> csrfExempt() {
        return csrfExempt;
    }

    List<String> noStore() {
        return noStore;
    }

    /** One declared rule. */
    public record Rule(Access access, HttpMethod method, String pattern) {}
}
