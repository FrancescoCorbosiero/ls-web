package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.platform.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * ADR 0031: the owner's 60 days are the default of {@code ls.security.sessions.customer.remember};
 * the deployed profiles (session cookie {@code Secure}) use the {@code __Secure-} prefixed cookie
 * with every flag, limited to the customer area; a cookie the app did not write is never read.
 */
@Tag("CUS-01")
class RememberedSignInServiceTest {

    @Test
    void theDefaultIsSixtyDaysAndZeroTurnsItOff() {
        assertThat(service(Map.of(), true).offeredDays()).isEqualTo(60);
        assertThat(service(Map.of(), true).enabled()).isTrue();
        assertThat(security(Map.of()).sessions().customer().idle()).isEqualTo(Duration.ofMinutes(60));
        assertThat(security(Map.of()).sessions().customer().absolute()).isEqualTo(Duration.ofHours(12));
        assertThat(service(Map.of("ls.security.sessions.customer.remember", "0"), true)
                        .enabled())
                .isFalse();
        assertThat(service(Map.of("ls.security.sessions.customer.remember", "0"), true)
                        .offeredDays())
                .isZero();
        assertThat(service(Map.of("ls.security.sessions.customer.remember", "30d"), true)
                        .offeredDays())
                .isEqualTo(30);
    }

    @Test
    void overHttpsTheCookieIsSecurePrefixedHttpOnlyLaxAndLimitedToTheCustomerArea() {
        RememberedSignInService secure = service(Map.of(), true);
        assertThat(secure.cookieName()).isEqualTo("__Secure-LS_REMEMBER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        secure.clearCookie(response);
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("__Secure-LS_REMEMBER=")
                .contains("Path=/area-clienti/", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=");

        RememberedSignInService local = service(Map.of(), false);
        assertThat(local.cookieName()).isEqualTo("LS_REMEMBER");
    }

    @Test
    void onlyItsOwnCookieWithAPlausibleValueIsRead() {
        RememberedSignInService service = service(Map.of(), true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(service.cookieToken(request)).isEmpty();

        request.setCookies(new Cookie("LS_REMEMBER", "token"), new Cookie("__Host-LS_SESSION", "id"));
        assertThat(service.cookieToken(request))
                .as("the plain name is not read over https")
                .isEmpty();

        request.setCookies(new Cookie("__Secure-LS_REMEMBER", "x".repeat(65)));
        assertThat(service.cookieToken(request)).as("far longer than a token").isEmpty();

        request.setCookies(new Cookie("__Secure-LS_REMEMBER", "abc"));
        assertThat(service.cookieToken(request)).contains("abc");
    }

    private static RememberedSignInService service(Map<String, String> values, boolean secure) {
        return new RememberedSignInService(null, null, security(values), secure, Clock.systemUTC());
    }

    private static SecurityProperties security(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ls.security", Bindable.of(SecurityProperties.class));
    }
}
