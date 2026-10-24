package it.logisticasanchez.web.site;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The page inventory of the public site (chapter 06 §3.1, PUB-01): one entry per page of
 * {@code legacy/ls-home/public/**}, the single source for the handler mappings
 * ({@link SitePageMappings}), the security routes ({@link SiteRouteContributor}), the sitemap
 * ({@link SiteMetaController}, PUB-03) and the trailing-slash redirects ({@link SiteRedirectFilter},
 * PUB-04). The templates were produced by {@code tools/port-ls-home-pages.py} and live under
 * {@code templates/site/<key>.html}.
 */
public final class SitePages {

    /** The content date printed as {@code <lastmod>} (chapter 06 §6.2: the static site's last change). */
    static final LocalDate CONTENT_DATE = LocalDate.of(2026, 10, 16);

    private static final List<Page> ALL = List.of(
            page("/", true),
            page("/servizi/", true),
            page("/servizi/spedizione-pallet/", true),
            page("/servizi/groupage/", true),
            page("/servizi/carico-completo/", true),
            page("/servizi/magazzino/", true),
            page("/zone/", true),
            page("/zone/monza-brianza/", true),
            page("/zone/milano/", true),
            page("/zone/como/", true),
            page("/zone/lecco/", true),
            page("/zone/bergamo/", true),
            page("/tracking/", true),
            page("/preventivo/", true),
            page("/contatti/", true),
            page("/chi-siamo/", true),
            page("/faq/", true),
            page("/blog/", true),
            page("/blog/come-preparare-un-pallet-per-la-spedizione/", true),
            page("/blog/misure-pallet-epal-industriale/", true),
            page("/blog/quanto-costa-spedire-un-pallet/", true),
            page("/blog/groupage-o-carico-completo/", true),
            page("/privacy/", true),
            page("/cookie/", true),
            page("/grazie/", false));

    private SitePages() {}

    private static Page page(String path, boolean indexable) {
        String key = path.equals("/") ? "home" : path.substring(1, path.length() - 1);
        return new Page(path, key, "site/" + key, indexable);
    }

    /** Every page, in the order of the inventory. */
    public static List<Page> all() {
        return ALL;
    }

    public static Optional<Page> byPath(String path) {
        for (Page page : ALL) {
            if (page.path().equals(path)) {
                return Optional.of(page);
            }
        }
        return Optional.empty();
    }

    /** The sitemap entries (chapter 06 §6.2): indexable pages, home first, then alphabetical. */
    public static List<Page> sitemap() {
        List<Page> pages = new ArrayList<>();
        for (Page page : ALL) {
            if (page.indexable()) {
                pages.add(page);
            }
        }
        pages.sort(Comparator.comparing((Page p) -> !p.path().equals("/")).thenComparing(Page::path));
        return pages;
    }

    /**
     * One public page: its URL (always with a trailing slash), the page key of the static site
     * ({@code data-page}, also the template name under {@code site/}), and whether it is indexed
     * ({@code /grazie/} is {@code noindex} and left out of the sitemap, PUB-09).
     */
    public record Page(String path, String key, String template, boolean indexable) {

        /** Sitemap priority of chapter 06 §6.2: 1.0 home, 0.3 legal pages, 0.8 first level, 0.6 deeper. */
        public String priority() {
            if (path.equals("/")) {
                return "1.0";
            }
            if (path.equals("/privacy/") || path.equals("/cookie/")) {
                return "0.3";
            }
            return path.indexOf('/', 1) == path.length() - 1 ? "0.8" : "0.6";
        }

        /** The URL without its trailing slash ({@code /zone}), redirected to the slash form (chapter 06 §6.8). */
        public String pathWithoutSlash() {
            return path.equals("/") ? null : path.substring(0, path.length() - 1);
        }
    }
}
