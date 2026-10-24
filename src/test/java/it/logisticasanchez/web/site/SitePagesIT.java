package it.logisticasanchez.web.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.security.SecurityHeadersFilter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PUB-01: every page of chapter 06 §3.1 answers 200 at its URL with the legacy {@code <title>},
 * canonical, one {@code <h1>}, its {@code data-page}, the active-menu attribute and the JSON-LD
 * graph; the legacy page's title, description, canonical, h1 and {@code <main>} are compared
 * with the checkout when it is present. PUB-03: sitemap, robots, security.txt and
 * {@code X-Robots-Tag}. PUB-09: unknown URLs and {@code /404.html} answer the legacy 404 page
 * with status 404; {@code /grazie/} is noindex. PUB-05: no inline script or style survives on
 * any page. TRK-01: {@code /tracking/} ships the widget markup over the local endpoint.
 */
@Tag("PUB-01")
@Tag("PUB-03")
@Tag("PUB-05")
@Tag("PUB-09")
@Tag("TRK-01")
@SpringBootTest
@AutoConfigureMockMvc
class SitePagesIT extends AbstractPostgresIT {

    private static final Path LEGACY = Path.of("legacy/ls-home/public");

    @Autowired
    private MockMvc mvc;

    static List<SitePages.Page> pages() {
        return SitePages.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void everyPageAnswers200WithItsLegacyMetadataAndContent(SitePages.Page page) throws Exception {
        MvcResult result = mvc.perform(get(page.path()).header("Host", "logisticasanchez.com"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.PUBLIC_CSP))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("<body data-page=\"" + dataPage(page) + "\">");
        assertThat(count(html, "<h1")).as("one h1").isEqualTo(1);
        assertThat(html).contains("<main id=\"main\">").contains("</main>");
        assertThat(html).contains("<title>").containsPattern("<title>[^<]*Logistica Sanchez[^<]*</title>");
        if (page.indexable()) {
            assertThat(html)
                    .contains("<link rel=\"canonical\" href=\"https://logisticasanchez.com" + page.path() + "\">")
                    .contains("<meta property=\"og:url\" content=\"https://logisticasanchez.com" + page.path() + "\">")
                    .contains("<meta name=\"robots\" content=\"index, follow, max-image-preview:large\">")
                    .contains("\"@id\":\"https://logisticasanchez.com/#business\"")
                    .contains("\"@id\":\"https://logisticasanchez.com" + page.path() + "#webpage\"");
            assertThat(result.getResponse().getHeader("X-Robots-Tag")).isNull();
        } else {
            assertThat(html)
                    .contains("<meta name=\"robots\" content=\"noindex, follow\">")
                    .doesNotContain("rel=\"canonical\"");
            assertThat(result.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
        }
        // the active menu item (tools/sync.mjs rule): the exact key when the header links it, else the
        // ancestor section (zone pages → «Zone servite»); the home has no nav link in the header
        String navKey = dataPage(page);
        if (html.contains("data-nav=\"" + navKey + "\"")) {
            assertThat(html).contains("data-nav=\"" + navKey + "\" aria-current=\"page\"");
        } else if (navKey.contains("/")) {
            String section = navKey.substring(0, navKey.indexOf('/'));
            assertThat(html).contains("data-nav=\"" + section + "\" aria-current=\"true\"");
        } else {
            assertThat(navKey).isIn("home", "grazie", "privacy", "cookie");
            assertThat(html).doesNotContainPattern("data-nav=\"[^\"]+\" aria-current");
        }
        assertThat(html)
                .contains("<script type=\"module\" src=\"/js/main.js\"></script>")
                .contains("<footer class=\"site-footer\">")
                .doesNotContain("Portale ordini")
                .doesNotContain("Effettua un ordine")
                .doesNotContain("ordini.logisticasanchez.com")
                .doesNotContain("[[")
                .doesNotContain("[(")
                .doesNotContainPattern("\\sth:[a-z]+=");
        // PUB-05: nothing the CSP would block
        assertThat(html).doesNotContain(" style=\"").doesNotContain("onclick=").doesNotContain("javascript:");
        Matcher scripts = Pattern.compile("<script\\b([^>]*)>").matcher(html);
        while (scripts.find()) {
            String attributes = scripts.group(1);
            assertThat(attributes)
                    .as("script tag %s", attributes)
                    .matches(a -> a.contains("src=\"/js/")
                            || a.contains("application/ld+json")
                            || a.contains("speculationrules"));
        }
        compareWithLegacy(page, html);
    }

    /** Title, description, canonical, h1 and {@code <main>} equal the legacy file, modulo the allowed differences (ADR 0012). */
    private static void compareWithLegacy(SitePages.Page page, String html) throws Exception {
        Path file = LEGACY.resolve(
                page.path().equals("/") ? "index.html" : page.path().substring(1) + "index.html");
        if (!Files.isRegularFile(file)) {
            return;
        }
        String legacy = Files.readString(file, StandardCharsets.UTF_8);
        List<String> tags = page.indexable()
                ? List.of("<title>", "<meta name=\"description\"", "<link rel=\"canonical\"", "<h1")
                : List.of("<title>", "<meta name=\"description\"", "<h1");
        for (String tag : tags) {
            assertThat(line(html, tag)).as("%s of %s", tag, page.path()).isEqualTo(line(legacy, tag));
        }
        // The texts added for the customer area (NFR-03, ADR 0024) are marked by the port script and left out.
        String ours = main(html)
                .replaceAll("<!-- ls-web:addition -->[\\s\\S]*?<!-- /ls-web:addition -->", "")
                .replaceAll("\\s+", " ");
        String theirs = main(legacy)
                .replace(
                        "href=\"https://ordini.logisticasanchez.com\" target=\"_blank\" rel=\"noopener\"",
                        "href=\"/area-clienti/\"")
                .replace("Effettua un ordine", "Area clienti")
                .replace("logisticasanchezmb@<wbr>gmail.com", "logisticasanchezmb@gmail.com")
                .replace("+39&nbsp;329&nbsp;239&nbsp;6962", "+39 329 239 6962")
                .replaceAll("\\s+", " ");
        assertSameText(page.path(), ours, theirs);
    }

    /** Equality with a readable message: the first differing position and its surroundings. */
    private static void assertSameText(String what, String ours, String theirs) {
        if (ours.equals(theirs)) {
            return;
        }
        int i = 0;
        while (i < ours.length() && i < theirs.length() && ours.charAt(i) == theirs.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 120);
        throw new AssertionError("<main> of " + what + " differs at " + i + ":\n ours:   "
                + ours.substring(from, Math.min(ours.length(), i + 200)) + "\n legacy: "
                + theirs.substring(from, Math.min(theirs.length(), i + 200)));
    }

    private static String line(String html, String tag) {
        int at = html.indexOf(tag);
        assertThat(at).as("has %s", tag).isGreaterThanOrEqualTo(0);
        int end = html.indexOf('>', at);
        if (tag.equals("<title>") || tag.equals("<h1")) {
            end = html.indexOf(tag.equals("<title>") ? "</title>" : "</h1>", at);
        }
        return html.substring(at, end).replaceAll("\\s+", " ");
    }

    private static String main(String html) {
        int from = html.indexOf("<main id=\"main\">");
        int to = html.indexOf("</main>", from) + "</main>".length();
        return html.substring(from, to);
    }

    /** The {@code data-page} of the static site: blog posts share {@code blog} (chapter 06 §3.1). */
    private static String dataPage(SitePages.Page page) {
        return page.key().startsWith("blog/") ? "blog" : page.key();
    }

    private static int count(String s, String needle) {
        return s.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void theSitemapListsThe24IndexablePagesAndNothingElse() throws Exception {
        String xml = mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(xml)
                .startsWith(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        List<String> locs = new ArrayList<>();
        Matcher m = Pattern.compile("<loc>([^<]+)</loc>").matcher(xml);
        while (m.find()) {
            locs.add(m.group(1));
        }
        assertThat(locs).hasSize(24).startsWith("https://logisticasanchez.com/", "https://logisticasanchez.com/blog/");
        assertThat(locs.subList(1, locs.size())).isSorted();
        assertThat(xml)
                .doesNotContain("/grazie/")
                .doesNotContain("404")
                .doesNotContain("/area-clienti/")
                .doesNotContain("/gestione/")
                .doesNotContain("/api/")
                .doesNotContain("/mono/");
        assertThat(xml)
                .contains(
                        "<loc>https://logisticasanchez.com/</loc>\n    <lastmod>2026-10-16</lastmod>\n    <priority>1.0</priority>")
                .contains(
                        "<loc>https://logisticasanchez.com/privacy/</loc>\n    <lastmod>2026-10-16</lastmod>\n    <priority>0.3</priority>")
                .contains(
                        "<loc>https://logisticasanchez.com/zone/</loc>\n    <lastmod>2026-10-16</lastmod>\n    <priority>0.8</priority>")
                .contains(
                        "<loc>https://logisticasanchez.com/zone/como/</loc>\n    <lastmod>2026-10-16</lastmod>\n    <priority>0.6</priority>");
        assertThat(count(xml, "<url>")).isEqualTo(24);
        if (Files.isRegularFile(LEGACY.resolve("sitemap.xml"))) {
            String legacy = Files.readString(LEGACY.resolve("sitemap.xml"), StandardCharsets.UTF_8);
            assertThat(xml.replaceAll("\\s+", "")).isEqualTo(legacy.replaceAll("\\s+", ""));
        }
    }

    @Test
    void robotsAndSecurityTxtComeFromTheConfiguration() throws Exception {
        mvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content()
                        .string("User-agent: *\nAllow: /\n\nSitemap: https://logisticasanchez.com/sitemap.xml\n"));
        for (String path : List.of("/.well-known/security.txt", "/security.txt")) {
            String txt = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/plain"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(txt)
                    .startsWith("Contact: mailto:logisticasanchezmb@gmail.com\nExpires: 20")
                    .contains(
                            "T00:00:00.000Z\nPreferred-Languages: it, en\nCanonical: https://logisticasanchez.com/.well-known/security.txt\n");
            Matcher expires =
                    Pattern.compile("Expires: (\\d{4}-\\d{2}-\\d{2})T").matcher(txt);
            assertThat(expires.find()).isTrue();
            java.time.LocalDate date = java.time.LocalDate.parse(expires.group(1));
            assertThat(date)
                    .isAfter(java.time.LocalDate.now().plusMonths(9))
                    .isBefore(java.time.LocalDate.now().plusYears(1));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/area-clienti/", "/gestione/", "/api/form", "/mono/api/enumeration/statoTracking", "/mono/x"})
    void thePrivateAreasTheApiAndTheCompatibilityPathsAreNeverIndexed(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        assertThat(result.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/pagina-che-non-esiste/", "/404.html", "/servizi/inesistente/", "/blog/post-inventato/"})
    void unknownUrlsAnswerTheLegacy404PageWithStatus404(String path) throws Exception {
        String html = mvc.perform(get(path).accept("text/html"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.PUBLIC_CSP))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("<title>Pagina non trovata | Logistica Sanchez</title>")
                .contains("<meta name=\"robots\" content=\"noindex, follow\">")
                .contains("<body data-page=\"404\">")
                .contains("<h1>Questo pallet si è perso per strada.</h1>")
                .contains("<p class=\"notfound-code\" aria-hidden=\"true\">404</p>")
                .contains("<a href=\"/tracking/\">Traccia la spedizione</a>")
                .doesNotContain("application/ld+json")
                .doesNotContain("aria-current")
                .doesNotContain("cta-band");
        assertThat(count(html, "<h1")).isEqualTo(1);
        if (Files.isRegularFile(LEGACY.resolve("404.html"))) {
            String legacy = Files.readString(LEGACY.resolve("404.html"), StandardCharsets.UTF_8);
            assertThat(main(html).replaceAll("\\s+", " "))
                    .isEqualTo(main(legacy).replaceAll("\\s+", " "));
        }
    }

    @Test
    void theTrackingPageShipsTheWidgetOverTheLocalEndpoint() throws Exception {
        String html = mvc.perform(get("/tracking/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html)
                .contains("<div data-tracking>")
                .contains("<form action=\"/tracking/\" method=\"get\">")
                .contains("name=\"code\"")
                .contains("data-track-result")
                .contains("<strong>Presa in carico</strong>")
                .contains("<strong>In transito</strong>")
                .contains("<strong>Terminal di destinazione</strong>")
                .contains("<strong>In consegna</strong>")
                .contains("<strong>Consegnata</strong>")
                .doesNotContain("app.logisticasanchez.com");
        String config =
                mvc.perform(get("/js/config.js")).andReturn().getResponse().getContentAsString();
        assertThat(config).contains("endpoint: '/mono/api/order/trace/state'");
        String js = Files.readString(Path.of("src/main/resources/static/js/tracking.js"), StandardCharsets.UTF_8);
        assertThat(js).contains("TRACKING.endpoint");
    }
}
