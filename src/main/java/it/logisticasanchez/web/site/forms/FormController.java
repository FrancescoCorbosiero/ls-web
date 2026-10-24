package it.logisticasanchez.web.site.forms;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.config.SiteProperties;
import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * {@code POST /api/form}: the quote and contact forms (PUB-06, PUB-07, PUB-11), with the
 * pipeline of {@code form.mjs:244-288} — JSON mode when {@code Accept} carries
 * {@code application/json} ({@code {"ok":true}} / {@code {"ok":false,"error":…}}), native mode
 * otherwise (303 to {@code /grazie/}, or the «Invio non riuscito» page with the same status);
 * 413 over 20,000 characters, honeypot {@code bot-field} → fake success, unknown form 400,
 * first invalid field 422 {@code invalid:<field>}, 502 {@code send_failed} when the lead and
 * the e-mail could not be queued. Added by the monolith (chapter 10 §4, ADR 0014): the
 * endpoint is CSRF-exempt and session-free, so it checks {@code Origin}/{@code Referer} against
 * the site's origins (403) and rate-limits per client IP (429).
 */
@Controller
public class FormController {

    public static final String PATH = "/api/form";
    static final int MAX_BODY = 20_000;
    static final String THANKS = "/grazie/";

    private static final Logger log = LoggerFactory.getLogger(FormController.class);

    private final FormValidator validator;
    private final LeadService leads;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final Set<String> siteOrigins;

    public FormController(
            FormValidator validator,
            LeadService leads,
            RateLimiter rateLimiter,
            ClientIpResolver clientIp,
            BusinessProperties business,
            SiteProperties site) {
        this.validator = validator;
        this.leads = leads;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        Set<String> origins = new HashSet<>();
        origins.add(business.siteUrl().toLowerCase(Locale.ROOT));
        origins.add("https://" + site.hosts().canonical().toLowerCase(Locale.ROOT));
        origins.add("https://" + site.hosts().www().toLowerCase(Locale.ROOT));
        origins.add("https://" + site.hosts().app().toLowerCase(Locale.ROOT));
        this.siteOrigins = Set.copyOf(origins);
    }

    @PostMapping(PATH)
    public Object submit(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        boolean wantsJson = accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json");
        Answer answer = new Answer(wantsJson, "/");

        if (request.getContentLengthLong() > MAX_BODY) {
            return answer.fail(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Il messaggio è troppo lungo.");
        }
        if (!fromTheSite(request)) {
            return answer.fail(
                    HttpStatus.FORBIDDEN,
                    "forbidden",
                    "La richiesta non arriva dal nostro sito: torna al modulo e riprova.");
        }
        String ip = clientIp.resolve(request);
        RateLimiter.Decision decision = rateLimiter.tryAcquire(RateLimitKeys.FORM_SUBMIT, ip);
        if (!decision.allowed()) {
            return answer.fail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "too_many_requests",
                    ErrorCode.TOO_MANY_REQUESTS.reason(),
                    Math.max(1, decision.retryAfter().toSeconds()));
        }

        Map<String, String[]> fields = request.getParameterMap();
        // Honeypot: people never see this field, bots fill it in. Pretend it worked.
        if (!first(fields, "bot-field").isBlank()) {
            return answer.done();
        }
        Optional<FormSubmission.Form> form =
                FormValidator.formOf(first(fields, "form-name").trim());
        if (form.isEmpty()) {
            return answer.fail(HttpStatus.BAD_REQUEST, "unknown_form", "Il modulo non è stato riconosciuto.");
        }
        answer = new Answer(wantsJson, form.get().page());

        FormSubmission submission;
        try {
            submission = validator.validate(form.get(), fields);
        } catch (InvalidFormException e) {
            return answer.fail(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "invalid:" + e.field(),
                    "Alcuni campi non sono compilati correttamente: torna al modulo e controllali.");
        }
        try {
            leads.store(submission, ip, request.getHeader(HttpHeaders.USER_AGENT));
        } catch (RuntimeException e) {
            log.error("[form] lead and e-mail not queued", e);
            return answer.fail(
                    HttpStatus.BAD_GATEWAY,
                    "send_failed",
                    "Non siamo riusciti a inoltrare la richiesta. Riprova tra poco.");
        }
        return answer.done();
    }

    /**
     * The request comes from one of the site's origins: {@code Origin} when present, else
     * {@code Referer}; the request's own origin (localhost, staging) is always accepted; a
     * request with neither header is refused (the endpoint has no CSRF token).
     */
    boolean fromTheSite(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            return allowedOrigin(origin, request);
        }
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null) {
            return false;
        }
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            return allowedOrigin(
                    uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()),
                    request);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean allowedOrigin(String origin, HttpServletRequest request) {
        String lower = origin.trim().toLowerCase(Locale.ROOT);
        if (siteOrigins.contains(lower)) {
            return true;
        }
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        String own = request.getScheme() + "://" + request.getServerName().toLowerCase(Locale.ROOT)
                + (defaultPort || port <= 0 ? "" : ":" + port);
        return lower.equals(own);
    }

    private static String first(Map<String, String[]> fields, String name) {
        String[] values = fields.get(name);
        return values == null || values.length == 0 || values[0] == null ? "" : values[0];
    }

    /** The two answer shapes of form.mjs: JSON for {@code fetch}, a redirect or an HTML page without JavaScript. */
    private record Answer(boolean json, String back) {

        Object done() {
            if (json) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("ok", true));
            }
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(THANKS))
                    .build();
        }

        Object fail(HttpStatus status, String error, String message) {
            return fail(status, error, message, null);
        }

        /** A failed answer, rendered as JSON or as the «Invio non riuscito» page. */
        Object fail(HttpStatus status, String error, String message, Long retryAfter) {
            if (json) {
                ResponseEntity.BodyBuilder builder =
                        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
                if (retryAfter != null) {
                    builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                }
                return builder.body(Map.of("ok", false, "error", error));
            }
            ModelAndView view = new ModelAndView("site/form-error", status);
            view.addObject("message", message);
            view.addObject("back", back);
            view.addObject("retryAfter", retryAfter);
            return view;
        }
    }
}
