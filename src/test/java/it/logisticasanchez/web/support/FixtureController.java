package it.logisticasanchez.web.support;

import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.security.CspNonce;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Endpoints that exist only in tests, to exercise the platform the way module routes will.
 * {@code @TestComponent} keeps it out of the application's component scan: it exists only in
 * the contexts that {@code @Import(TestFixtures.class)}.
 */
@TestComponent
@Controller
public class FixtureController {

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;

    public FixtureController(RateLimiter rateLimiter, ClientIpResolver clientIp) {
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
    }

    @GetMapping(value = "/fixture/public", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String publicPage() {
        return "<!DOCTYPE html><html lang=\"it\"><head><title>Fixture</title></head><body><main id=\"main\"><h1>Pagina pubblica</h1></main></body></html>";
    }

    @GetMapping("/fixture/forwarded")
    @ResponseBody
    public Map<String, String> forwarded(HttpServletRequest request) {
        return Map.of(
                "clientIp", clientIp.resolve(request),
                "remoteAddr", request.getRemoteAddr(),
                "scheme", request.getScheme(),
                "serverName", request.getServerName(),
                "url", request.getRequestURL().toString());
    }

    @GetMapping("/fixture/ratelimit")
    @ResponseBody
    public Map<String, String> rateLimited(HttpServletRequest request) {
        rateLimiter.require(RateLimitKeys.FORM_SUBMIT, clientIp.resolve(request));
        return Map.of("ok", "true");
    }

    @PostMapping("/fixture/form")
    @ResponseBody
    public Map<String, String> form(@RequestParam("email") String email) {
        return Map.of("received", "true");
    }

    @GetMapping("/area-clienti/fixture")
    public String customerPage(HttpServletRequest request, Model model) {
        model.addAttribute("nonce", CspNonce.of(request));
        return "fixture/private";
    }

    @PostMapping("/area-clienti/fixture")
    @ResponseBody
    public Map<String, String> customerPost() {
        return Map.of("posted", "true");
    }

    @GetMapping("/gestione/fixture")
    @ResponseBody
    public Map<String, String> staffPage() {
        return Map.of("area", "staff");
    }

    @PostMapping("/gestione/fixture")
    @ResponseBody
    public Map<String, String> staffPost() {
        return Map.of("posted", "true");
    }

    @GetMapping("/gestione/fixture/admin")
    @ResponseBody
    public Map<String, String> adminPage() {
        return Map.of("area", "admin");
    }

    // --- error model ---

    public record Payload(@NotBlank String name) {}

    @PostMapping(value = "/fixture/errors/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, String> json(@Valid @RequestBody Payload payload) {
        return Map.of("name", payload.name());
    }

    @GetMapping("/fixture/errors/boom")
    @ResponseBody
    public String boom() {
        throw new IllegalStateException("secret internal detail that must never reach the client");
    }

    @GetMapping("/fixture/errors/not-found")
    @ResponseBody
    public String notFound() {
        throw new ClientErrorException(ErrorCode.NOT_FOUND);
    }

    @GetMapping("/fixture/errors/conflict")
    @ResponseBody
    public String conflict() {
        throw new ClientErrorException(ErrorCode.CODE_CONFLICT);
    }

    @PostMapping("/fixture/errors/stale")
    @ResponseBody
    public String stale() {
        throw new ObjectOptimisticLockingFailureException("ShipmentOrder", 1L);
    }

    @GetMapping("/fixture/errors/param")
    @ResponseBody
    public String param(@RequestParam("n") int n) {
        return Integer.toString(n);
    }
}
