package it.logisticasanchez.web.site;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code sitemap.xml}, {@code robots.txt} and {@code security.txt} generated from the page
 * inventory and the business configuration (PUB-03, chapter 06 §6.2 and §6.7): the sitemap
 * lists the indexable pages (home first, then alphabetical) with the priorities of §6.2 and
 * the content date; {@code robots.txt} is the legacy file with the configured site URL;
 * {@code security.txt} (RFC 9116) takes its contact from {@code ls.business.office-email} and
 * renews {@code Expires} by itself (the first day of the month eleven months ahead).
 */
@RestController
public class SiteMetaController {

    private static final MediaType TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private final BusinessProperties business;
    private final Clock clock;

    public SiteMetaController(BusinessProperties business, Clock clock) {
        this.business = business;
        this.clock = clock;
    }

    @GetMapping(value = "/sitemap.xml")
    public ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (SitePages.Page page : SitePages.sitemap()) {
            xml.append("  <url>\n")
                    .append("    <loc>")
                    .append(business.siteUrl())
                    .append(page.path())
                    .append("</loc>\n")
                    .append("    <lastmod>")
                    .append(SitePages.CONTENT_DATE)
                    .append("</lastmod>\n")
                    .append("    <priority>")
                    .append(page.priority())
                    .append("</priority>\n")
                    .append("  </url>\n");
        }
        xml.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        String body = "User-agent: *\nAllow: /\n\nSitemap: " + business.siteUrl() + "/sitemap.xml\n";
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(body);
    }

    @GetMapping({"/.well-known/security.txt", "/security.txt"})
    public ResponseEntity<String> securityTxt() {
        String body = "Contact: mailto:" + business.officeEmail() + "\n"
                + "Expires: " + expires() + "T00:00:00.000Z\n"
                + "Preferred-Languages: it, en\n"
                + "Canonical: " + business.siteUrl() + "/.well-known/security.txt\n";
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(body);
    }

    /** RFC 9116 wants {@code Expires} less than a year out: the first day of the month, eleven months ahead. */
    LocalDate expires() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).plusMonths(11);
    }
}
