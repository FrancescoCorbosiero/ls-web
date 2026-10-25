package it.logisticasanchez.web.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.logisticasanchez.web.platform.config.CompatProperties;
import it.logisticasanchez.web.platform.ratelimit.RateLimitKeys;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.platform.security.ClientIpResolver;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The kept endpoints of the legacy API (chapter 02 §10.1–10.2, ADR 0010): the tracking lookup
 * EP-13 ({@code POST /mono/api/order/trace/state {"codice"}} → {@code 200 {"status": <label>}},
 * «Non gestito» for unknown and temporary codes, 400 for an empty code; API-01, TRK-01…04) and
 * the two enumerations EP-19/EP-20 (API-02), with exactly the legacy JSON shape. Lookups are
 * rate-limited per client IP (TRK-04); unknown JSON fields are ignored unless
 * {@code ls.compat.strict-json} is set (PD-09). CORS is handled by {@link CompatCorsFilter}.
 */
@RestController
public class CompatApiController {

    public static final String TRACE_PATH = "/mono/api/order/trace/state";
    public static final String TRACKING_STATES_PATH = "/mono/api/enumeration/statoTracking";
    public static final String ACCESSORY_SERVICES_PATH = "/mono/api/enumeration/servizioAccessorio";

    private final TrackingLookupService lookup;
    private final TrackingStateRepository states;
    private final AccessoryServiceRepository services;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final boolean strictJson;

    public CompatApiController(
            TrackingLookupService lookup,
            TrackingStateRepository states,
            AccessoryServiceRepository services,
            RateLimiter rateLimiter,
            ClientIpResolver clientIp,
            CompatProperties compat) {
        this.lookup = lookup;
        this.states = states;
        this.services = services;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.strictJson = compat.strictJson(); // PD-09
    }

    @PostMapping(value = TRACE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceResponse trace(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        if (body == null) {
            throw new ClientErrorException(ErrorCode.BAD_REQUEST);
        }
        if (strictJson && !Set.of("codice").containsAll(body.keySet())) {
            throw new ClientErrorException(ErrorCode.BAD_REQUEST);
        }
        Object codice = body.get("codice");
        if (!(codice instanceof String code) || code.isBlank()) {
            throw new ClientErrorException(ErrorCode.BAD_REQUEST);
        }
        rateLimiter.require(RateLimitKeys.TRACKING_LOOKUP, clientIp.resolve(request));
        return lookup.lookup(code)
                .map(t -> new TraceResponse(
                        t.status(), t.updatedAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(t.updatedAt())))
                .orElseGet(() -> new TraceResponse(TrackingLookupService.NOT_HANDLED, null));
    }

    @GetMapping(value = TRACKING_STATES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<EnumItem> trackingStates() {
        return states.findAllByOrderByIdAsc().stream()
                .map(s -> new EnumItem(s.getId(), s.getName(), s.getLabel()))
                .toList();
    }

    @GetMapping(value = ACCESSORY_SERVICES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<EnumItem> accessoryServices() {
        return services.findAllByOrderBySortOrderAsc().stream()
                .map(s -> new EnumItem(s.getId(), s.getName(), s.getLabel()))
                .toList();
    }

    /**
     * The EP-13 answer: {@code status} is always a non-empty label; {@code updatedAt} (ISO-8601,
     * UTC) is the only extra field, present when the state has changed at least once (TRK-03).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TraceResponse(String status, String updatedAt) {}

    /** The EP-19/EP-20 item: {@code {id, name, label}} with a numeric id (chapter 02 §9). */
    public record EnumItem(int id, String name, String label) {}
}
