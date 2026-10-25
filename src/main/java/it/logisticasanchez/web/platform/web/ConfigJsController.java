package it.logisticasanchez.web.platform.web;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /js/config.js} of the static site (PUB-02), rendered from the business configuration
 * instead of a copied file: the widgets ({@code forms.js}, {@code tracking.js}, {@code quote.js},
 * …) import it unchanged. Same exports as {@code legacy/ls-home/public/js/config.js}; the
 * tracking endpoint is the same-origin compatibility path and the forms post to {@code /api/form}.
 */
@RestController
public class ConfigJsController {

    public static final String PATH = "/js/config.js";
    public static final String TRACKING_ENDPOINT = "/mono/api/order/trace/state";
    public static final String FORMS_ENDPOINT = "/api/form";

    private final BusinessProperties business;

    public ConfigJsController(BusinessProperties business) {
        this.business = business;
    }

    private static final MediaType JAVASCRIPT = MediaType.parseMediaType("text/javascript;charset=UTF-8");

    /** Answers like a static file: the same body whatever the {@code Accept} header. */
    @GetMapping(PATH)
    public ResponseEntity<String> configJs() {
        return ResponseEntity.ok().contentType(JAVASCRIPT).body(render());
    }

    String render() {
        String phoneDisplay = business.phone().replace(' ', ' ');
        String hours = "Lun–Ven " + business.hours().weekdays() + ", Sab "
                + business.hours().saturday();
        return """
                /** Logistica Sanchez — site configuration shared by the widgets (generated from ls.business.*). */
                export const BUSINESS = {
                  name: %s,
                  legalName: %s,
                  vat: %s,
                  address: %s,
                  phone: %s,
                  phoneDisplay: %s,
                  whatsapp: %s,
                  email: %s,
                  hours: %s,
                  siteUrl: %s,
                };

                /** Tracking widget: POST {"codice"} to the same-origin compatibility endpoint. */
                export const TRACKING = {
                  endpoint: %s,
                  portalUrl: '',
                  demoCode: '',
                  timeoutMs: 8000,
                };

                /** Quote and contact forms post here (url-encoded, JSON answer). */
                export const FORMS = {
                  endpoint: %s,
                };

                export const waLink = (text) => `https://wa.me/${BUSINESS.whatsapp}?text=${encodeURIComponent(text)}`;
                export const mailLink = (subject, body = '') =>
                  `mailto:${BUSINESS.email}?subject=${encodeURIComponent(subject)}${body ? `&body=${encodeURIComponent(body)}` : ''}`;

                export const escapeHtml = (value) =>
                  String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

                export const icon = (name, cls = 'icon') =>
                  `<svg class="${cls}" aria-hidden="true"><use href="/img/icons.svg#i-${name}"/></svg>`;
                """.formatted(
                        js(business.brandName()),
                        js(business.legalName()),
                        js(business.vatId()),
                        js(business.address().oneLine()),
                        js(business.phoneDigits()),
                        js(phoneDisplay),
                        js(business.whatsapp()),
                        js(business.officeEmail()),
                        js(hours),
                        js(business.siteUrl()),
                        js(TRACKING_ENDPOINT),
                        js(FORMS_ENDPOINT));
    }

    /** A JavaScript single-quoted string literal. */
    static String js(String value) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '<' -> sb.append("\\u003C");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }
}
