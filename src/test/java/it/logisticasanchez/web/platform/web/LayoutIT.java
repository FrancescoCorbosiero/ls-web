package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.security.SecurityHeadersFilter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PUB-02: the layout fragments and {@code config.js} render the business configuration — the
 * phone, office e-mail and VAT number of this context are overridden, and header, footer,
 * JSON-LD, {@code config.js} and the error page all show the overridden values (never the
 * defaults); «Portale ordini» became «Area clienti» → {@code /area-clienti/}; the static
 * resources of the legacy site are served at the same URLs with their cache rules; the private
 * layout carries the design system without any script (strict CSP) and the HTMX meta tags.
 */
@Tag("PUB-02")
@SpringBootTest(
        properties = {
            "ls.business.phone=+39 02 1234 5678",
            "ls.business.whatsapp=390212345678",
            "ls.business.office-email=ufficio@logistica.example",
            "ls.business.vat-number=00000000000",
            "ls.business.site-url=https://sito.example"
        })
@AutoConfigureMockMvc
class LayoutIT extends AbstractPostgresIT {

    private static final String DEFAULT_PHONE_DIGITS = "+393292396962";

    @Autowired
    private MockMvc mvc;

    @Test
    void thePublicLayoutRendersTheConfiguredBusinessData() throws Exception {
        String html = mvc.perform(get("/pagina-che-non-esiste/").accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.PUBLIC_CSP))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // header + top bar + footer read ls.business.*
        assertThat(html)
                .contains("href=\"tel:+390212345678\"")
                .contains("+39 02 1234 5678")
                .contains("href=\"mailto:ufficio@logistica.example\"")
                .contains("https://wa.me/390212345678?text=")
                .contains("P.IVA 00000000000")
                .doesNotContain(DEFAULT_PHONE_DIGITS)
                .doesNotContain("logisticasanchezmb@gmail.com")
                .doesNotContain("13113080967");
        // the only WhatsApp number on the page is the configured one
        assertThat(html.split("wa\\.me/390212345678").length - 1).isGreaterThanOrEqualTo(4);
        assertThat(html).doesNotContain("wa.me/393292396962");
        // «Portale ordini» → «Area clienti», active-menu attributes absent on an error page
        assertThat(html)
                .contains("href=\"/area-clienti/\"")
                .contains("Area clienti")
                .doesNotContain("Portale ordini")
                .doesNotContain("Effettua un ordine")
                .doesNotContain("aria-current");
        // the fragments: head (manifest, fonts, module script), header, footer with the year (no SEO scripts, no CTA
        // band on an error page)
        assertThat(html)
                .contains("<link rel=\"manifest\" href=\"/site.webmanifest\">")
                .contains("<script type=\"module\" src=\"/js/main.js\"></script>")
                .doesNotContain("<script type=\"speculationrules\">")
                .doesNotContain("application/ld+json")
                .contains("data-nav=\"servizi\"")
                .contains("© 20")
                .doesNotContain("© 2.0");
    }

    @Test
    void thePublicPagesRenderTheConfiguredBusinessDataInTheirBodyToo() throws Exception {
        // PUB-02 on a real page (site module): the contact page's hero, cards, meta description and
        // JSON-LD read the same configuration as the fragments; the defaults never appear.
        String html = mvc.perform(get("/contatti/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("href=\"tel:+390212345678\"")
                .contains("+39\u00a002\u00a01234\u00a05678")
                .contains("href=\"mailto:ufficio@logistica.example\"")
                .contains("https://wa.me/390212345678?text=")
                .contains("<link rel=\"canonical\" href=\"https://sito.example/contatti/\">")
                .contains("\"@id\":\"https://sito.example/contatti/#webpage\"")
                .contains("telefono +39 02 1234 5678, email ufficio@logistica.example")
                .doesNotContain(DEFAULT_PHONE_DIGITS)
                .doesNotContain("logisticasanchezmb@gmail.com")
                .doesNotContain("https://logisticasanchez.com")
                .doesNotContain("wa.me/393292396962");
    }

    @Test
    void configJsIsGeneratedFromTheConfigurationWithTheSameOriginEndpoints() throws Exception {
        String js = mvc.perform(get("/js/config.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.PUBLIC_CSP))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(js)
                .contains("export const BUSINESS = {")
                .contains("phone: '+390212345678'")
                .contains("whatsapp: '390212345678'")
                .contains("email: 'ufficio@logistica.example'")
                .contains("siteUrl: 'https://sito.example'")
                .contains("hours: 'Lun–Ven 8:00–18:00, Sab 8:00–12:00'")
                .contains("export const TRACKING = {")
                .contains("endpoint: '/mono/api/order/trace/state'")
                .contains("export const FORMS = {")
                .contains("endpoint: '/api/form'")
                .contains("export const waLink")
                .contains("export const mailLink")
                .contains("export const escapeHtml")
                .contains("export const icon")
                .doesNotContain("app.logisticasanchez.com")
                .doesNotContain(DEFAULT_PHONE_DIGITS);
    }

    @ParameterizedTest
    @CsvSource({
        "/css/main.css, text/css, no-cache",
        "/js/main.js, text/javascript, no-cache",
        "/js/forms.js, text/javascript, no-cache",
        "/img/icons.svg, image/svg+xml, 'public, max-age=604800'",
        "/img/brand/mark.svg, image/svg+xml, 'public, max-age=604800'",
        "/fonts/barlow-400.woff2, font/woff2, 'public, max-age=31536000, immutable'",
        "/favicon.svg, image/svg+xml, no-cache",
        "/favicon.ico, image/x-icon, no-cache",
        "/icon-192.png, image/png, 'public, max-age=604800'",
        "/apple-touch-icon.png, image/png, 'public, max-age=604800'",
        "/site.webmanifest, application/manifest+json, no-cache",
        "/robots.txt, text/plain, no-cache",
        "/.well-known/security.txt, text/plain, no-cache"
    })
    void theStaticResourcesOfTheLegacySiteAreServedAtTheSameUrls(String path, String type, String cacheControl)
            throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(type))
                .andExpect(header().string("Cache-Control", cacheControl))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.PUBLIC_CSP));
    }

    @Test
    void thePrivateLayoutUsesTheDesignSystemWithoutScriptsAndWithTheHtmxMeta() throws Exception {
        String html = mvc.perform(get("/gestione/").accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("<link rel=\"stylesheet\" href=\"/css/main.css\">")
                .contains("<meta name=\"robots\" content=\"noindex, nofollow\">")
                .contains("<meta name=\"htmx-config\"")
                .contains("class=\"site-header\"")
                .contains("<span class=\"brand-tag\">Gestione</span>")
                .contains("href=\"mailto:ufficio@logistica.example\"")
                .contains("P.IVA 00000000000")
                .doesNotContain("<script")
                .doesNotContain("Portale ordini");
        String customer = mvc.perform(get("/area-clienti/").accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(customer)
                .contains("<span class=\"brand-tag\">Area clienti</span>")
                .contains("Inserisci la tua e-mail")
                .doesNotContain("<script");
    }
}
