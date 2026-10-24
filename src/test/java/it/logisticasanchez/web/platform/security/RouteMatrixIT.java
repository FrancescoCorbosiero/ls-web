package it.logisticasanchez.web.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

/**
 * NFR-02 (chapter 10 §4 "deny by default, with a test per route"): the route × role matrix is
 * generated from the real handler mappings and checked against
 * {@code src/test/resources/security/route-matrix.tsv} for anonymous, CUSTOMER, OPERATOR and
 * ADMIN. A route missing from the table fails; so does a stale line. Nothing ever answers 500,
 * and a route under the private areas never answers 200 without the right session.
 */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
class RouteMatrixIT extends AbstractPostgresIT {

    private static final List<String> ROLES = List.of("anonymous", "CUSTOMER", "OPERATOR", "ADMIN");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Test
    void everyMappedRouteBehavesAsTheTableSaysForEveryRole() throws Exception {
        Map<String, String[]> expected = readTable();
        Set<String> mapped = mappedRoutes();

        assertThat(mapped)
                .as("every mapped route has a line in route-matrix.tsv (add the new route with its expected outcomes)")
                .isSubsetOf(expected.keySet());
        assertThat(expected.keySet())
                .as("every line of route-matrix.tsv corresponds to a mapped route (remove stale lines)")
                .isSubsetOf(mapped);

        List<String> problems = new ArrayList<>();
        for (String route : new TreeSet<>(mapped)) {
            String[] parts = route.split(" ", 2);
            String method = parts[0];
            String pattern = parts[1];
            String path = samplePath(pattern);
            String[] outcomes = expected.get(route);
            for (int i = 0; i < ROLES.size(); i++) {
                int status = mvc.perform(request(method, path, ROLES.get(i)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (status >= 500) {
                    problems.add(route + " as " + ROLES.get(i) + " answered " + status);
                }
                if (RouteRegistry.isPrivateArea(path) && status == 200 && "anonymous".equals(ROLES.get(i))) {
                    problems.add(route + " answered 200 to an anonymous request");
                }
                if (!matches(outcomes[i], status)) {
                    problems.add(route + " as " + ROLES.get(i) + ": expected " + outcomes[i] + ", got " + status);
                }
            }
        }
        assertThat(problems).as("route × role matrix").isEmpty();
    }

    private MockHttpServletRequestBuilder request(String method, String path, String role) {
        HttpMethod httpMethod = "ANY".equals(method) ? HttpMethod.GET : HttpMethod.valueOf(method);
        MockHttpServletRequestBuilder builder =
                MockMvcRequestBuilders.request(httpMethod, path).accept("text/html, application/json");
        if (!"anonymous".equals(role)) {
            builder.with(user("matrix-user").roles(role));
        }
        return builder;
    }

    private Set<String> mappedRoutes() {
        Set<String> routes = new TreeSet<>();
        for (RequestMappingInfoHandlerMapping mapping :
                context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                    mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = entry.getKey();
                Set<String> patterns = new TreeSet<>();
                if (info.getPathPatternsCondition() != null) {
                    for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                        patterns.add(pattern.getPatternString());
                    }
                }
                Set<String> methods = new TreeSet<>();
                info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
                if (methods.isEmpty()) {
                    methods.add("ANY");
                }
                for (String pattern : patterns) {
                    for (String method : methods) {
                        routes.add(method + " " + pattern);
                    }
                }
            }
        }
        return routes;
    }

    static String samplePath(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", "1").replace("/**", "/x").replace("/*", "/x");
    }

    static boolean matches(String expectation, int status) {
        for (String alternative : expectation.split("\\|")) {
            if (Integer.parseInt(alternative.trim()) == status) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String[]> readTable() throws IOException {
        Map<String, String[]> table = new LinkedHashMap<>();
        for (String line :
                Files.readAllLines(Path.of("src/test/resources/security/route-matrix.tsv"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t");
            assertThat(columns).as("columns of: %s", line).hasSize(6);
            table.put(columns[0] + " " + columns[1], new String[] {columns[2], columns[3], columns[4], columns[5]});
        }
        return table;
    }
}
