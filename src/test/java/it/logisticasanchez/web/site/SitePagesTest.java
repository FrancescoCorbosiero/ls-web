package it.logisticasanchez.web.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** PUB-03, PUB-04: the page inventory, its sitemap rules (chapter 06 §6.2) and the redirect table. */
@Tag("PUB-03")
@Tag("PUB-04")
class SitePagesTest {

    @Test
    void theInventoryHasThe25PagesOfChapter06WithTrailingSlashes() {
        assertThat(SitePages.all()).hasSize(25);
        for (SitePages.Page page : SitePages.all()) {
            assertThat(page.path()).startsWith("/").endsWith("/");
            assertThat(page.template()).isEqualTo("site/" + page.key());
            assertThat(SitePages.byPath(page.path())).contains(page);
        }
        assertThat(SitePages.byPath("/").orElseThrow().key()).isEqualTo("home");
        assertThat(SitePages.byPath("/servizi/groupage/").orElseThrow().key()).isEqualTo("servizi/groupage");
        assertThat(SitePages.byPath("/404.html")).isEmpty();
    }

    @Test
    void theSitemapListsThe24IndexablePagesHomeFirstThenAlphabeticalWithThePriorities() {
        List<SitePages.Page> sitemap = SitePages.sitemap();
        assertThat(sitemap).hasSize(24);
        assertThat(sitemap.get(0).path()).isEqualTo("/");
        assertThat(sitemap.get(0).priority()).isEqualTo("1.0");
        List<String> rest = sitemap.subList(1, sitemap.size()).stream()
                .map(SitePages.Page::path)
                .toList();
        assertThat(rest).isSorted().doesNotContain("/grazie/");
        assertThat(rest.get(0)).isEqualTo("/blog/");
        assertThat(SitePages.byPath("/privacy/").orElseThrow().priority()).isEqualTo("0.3");
        assertThat(SitePages.byPath("/cookie/").orElseThrow().priority()).isEqualTo("0.3");
        assertThat(SitePages.byPath("/zone/").orElseThrow().priority()).isEqualTo("0.8");
        assertThat(SitePages.byPath("/zone/como/").orElseThrow().priority()).isEqualTo("0.6");
        assertThat(SitePages.byPath("/blog/quanto-costa-spedire-un-pallet/")
                        .orElseThrow()
                        .priority())
                .isEqualTo("0.6");
    }

    @Test
    void theRedirectTableHoldsTheLegacyRulesAndTheTrailingSlashForms() {
        Map<String, String> table = SiteRedirectFilter.buildTable();
        assertThat(table).containsAllEntriesOf(SiteRedirectFilter.LEGACY_REDIRECTS);
        assertThat(SiteRedirectFilter.LEGACY_REDIRECTS).hasSize(10);
        assertThat(table)
                .containsEntry("/index.html", "/")
                .containsEntry("/zone", "/zone/")
                .containsEntry("/zone/index.html", "/zone/")
                .containsEntry("/servizi/groupage", "/servizi/groupage/")
                .containsEntry("/grazie", "/grazie/")
                .doesNotContainKey("/")
                .doesNotContainKey("/mono/*");
        // no rule points at a URL that is not a page
        for (String target : table.values()) {
            assertThat(SitePages.byPath(target))
                    .as("target %s is a page", target)
                    .isPresent();
        }
    }
}
