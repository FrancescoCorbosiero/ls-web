package it.logisticasanchez.web.site.notices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffPrincipal;
import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.MutableClock;
import it.logisticasanchez.web.support.Sessions;
import it.logisticasanchez.web.support.StaffTestData;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ENH-17 with {@code site.noticeBanner} on (ADR 0041): an ADMIN publishes a notice from «Avvisi sul
 * sito» and every public page shows it in the header, rendered by the server with no inline style
 * or script, only within its period (Europe/Rome); «Chiudi ora» and «Elimina» take it away; links
 * are a page of the site or an https address; the private areas never show it.
 */
@Tag("ENH-17")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Configuration.class)
@TestPropertySource(properties = "ls.flags.override.[site.noticeBanner]=true")
class SiteNoticesIT extends AbstractPostgresIT {

    private static final String NOTICES = "/gestione/impostazioni/avvisi/";
    private static final Pattern BANNER = Pattern.compile("<div class=\"site-notice[^\"]*\"[\\s\\S]*?</div>\\s*</div>");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private StaffUser adminUser;
    private String text;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-12-20T09:00:00Z"));
        // one notice at a time on the site: earlier tests' notices end before this one starts
        jdbc.update(
                "update site_notice set ends_at = ?, starts_at = null where ends_at is null or ends_at > ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)),
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
        adminUser = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        text = "Il 24 dicembre l'ufficio chiude alle 12 "
                + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void aNoticePublishedByAnAdminShowsOnEveryPublicPage() throws Exception {
        assertThat(page(get("/gestione/impostazioni/").session(admin()))).contains("data-notices-link");
        assertThat(banner(page(get("/")))).isNull();

        mvc.perform(post(NOTICES)
                        .session(admin())
                        .with(csrf())
                        .param("testo", "  " + text + "  ")
                        .param("link", "/contatti/")
                        .param("etichetta", "Orari e contatti")
                        .param("livello", "WARNING"))
                .andExpect(redirectedUrl(NOTICES))
                .andExpect(flash().attribute(
                                "flash", "Avviso salvato: le pagine del sito lo mostrano nel periodo scelto."));

        for (String path : new String[] {"/", "/servizi/", "/contatti/", "/tracking/"}) {
            String banner = banner(page(get(path)));
            assertThat(banner).as(path).isNotNull();
            assertThat(banner)
                    .contains("site-notice-warning")
                    .contains(text.replace("'", "&#39;"))
                    .contains("href=\"/contatti/\"")
                    .contains(">Orari e contatti</a>")
                    .doesNotContain("style=")
                    .doesNotContain("<script");
        }
        assertThat(page(get("/area-clienti/"))).doesNotContain("data-site-notice");
        assertThat(page(get(NOTICES).session(admin()))).contains("Sul sito ora").contains("Chiudi ora");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'site.notice.create' and entity_type = 'site_notice'"
                                + " and entity_id = (select max(id) from site_notice)",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aNoticeShowsOnlyWithinItsPeriodAndUntilItIsEndedOrDeleted() throws Exception {
        create(text, "2026-12-21T08:00", "2026-12-24T12:00");
        assertThat(banner(page(get("/")))).as("not yet").isNull();
        assertThat(page(get(NOTICES).session(admin()))).contains("Programmato");

        clock.set(Instant.parse("2026-12-21T07:00:00Z")); // 08:00 in Rome
        assertThat(banner(page(get("/")))).contains(text.substring(text.length() - 6));
        clock.set(Instant.parse("2026-12-24T11:00:00Z")); // 12:00 in Rome: the end
        assertThat(banner(page(get("/")))).isNull();
        assertThat(page(get(NOTICES).session(admin()))).contains("Concluso");

        String other = "Ritardi sulle consegne in Lombardia "
                + UUID.randomUUID().toString().substring(0, 6);
        long id = create(other, "", "");
        assertThat(banner(page(get("/")))).contains(other.substring(other.length() - 6));
        mvc.perform(post(NOTICES + id + "/chiudi").session(admin()).with(csrf()))
                .andExpect(redirectedUrl(NOTICES))
                .andExpect(flash().attribute("flash", "Avviso chiuso: non compare più sul sito."));
        assertThat(banner(page(get("/")))).isNull();
        mvc.perform(post(NOTICES + id + "/chiudi").session(admin()).with(csrf()))
                .andExpect(flash().attribute("flashError", "L'avviso era già concluso."));
        mvc.perform(post(NOTICES + id + "/elimina").session(admin()).with(csrf()))
                .andExpect(redirectedUrl(NOTICES));
        assertThat(jdbc.queryForObject("select count(*) from site_notice where id = ?", Integer.class, id))
                .isZero();
        mvc.perform(post(NOTICES + id + "/elimina").session(admin()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theFormRefusesUnsafeLinksAndImpossiblePeriods() throws Exception {
        int before = jdbc.queryForObject("select count(*) from site_notice", Integer.class);
        assertThat(refused("javascript:alert(1)", "Apri", "", "")).contains("Il link è una pagina del sito");
        assertThat(refused("//esempio.example/", "Apri", "", "")).contains("Il link è una pagina del sito");
        assertThat(refused("http://esempio.example/", "Apri", "", "")).contains("Il link è una pagina del sito");
        assertThat(refused("/contatti/\" onmouseover=\"x", "Apri", "", "")).contains("Il link è una pagina del sito");
        assertThat(refused("", "Apri", "", "")).contains("Inserisci l&#39;indirizzo del link");
        assertThat(refused("/contatti/", "", "", "")).contains("Scrivi il testo del link");
        assertThat(refused("", "", "2026-12-23T10:00", "2026-12-22T10:00"))
                .contains("La fine deve venire dopo l&#39;inizio.");
        assertThat(refused("", "", "", "2026-12-19T10:00")).contains("La fine è già passata");
        assertThat(refused("", "", "domani", "")).contains("Inserisci data e ora");
        assertThat(page(post(NOTICES).session(admin()).with(csrf()).param("testo", "x".repeat(301))))
                .contains("Il testo può avere al massimo 300 caratteri.");
        assertThat(jdbc.queryForObject("select count(*) from site_notice", Integer.class))
                .isEqualTo(before);
        assertThat(SiteNoticeService.isAllowedLink("https://www.esempio.example/pagina?x=1"))
                .isTrue();
        assertThat(SiteNoticeService.isAllowedLink("/zone/milano/")).isTrue();

        StaffUser operatorUser = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        MockHttpSession operator =
                Sessions.staff(new StaffPrincipal(operatorUser, clock.instant()), "OPERATOR", clock.instant());
        mvc.perform(get(NOTICES).session(operator).accept("text/html")).andExpect(status().isForbidden());
    }

    private long create(String message, String from, String to) throws Exception {
        mvc.perform(post(NOTICES)
                        .session(admin())
                        .with(csrf())
                        .param("testo", message)
                        .param("livello", "INFO")
                        .param("dal", from)
                        .param("al", to))
                .andExpect(redirectedUrl(NOTICES));
        clock.advance(Duration.ZERO);
        return jdbc.queryForObject("select max(id) from site_notice", Long.class);
    }

    private String refused(String link, String label, String from, String to) throws Exception {
        return page(post(NOTICES)
                .session(admin())
                .with(csrf())
                .param("testo", text)
                .param("link", link)
                .param("etichetta", label)
                .param("dal", from)
                .param("al", to));
    }

    /** A staff session opened at the test clock's now: the clock moves days ahead, past the absolute limit. */
    private MockHttpSession admin() {
        return Sessions.staff(new StaffPrincipal(adminUser, clock.instant()), "ADMIN", clock.instant());
    }

    /** The banner of a public page, or null when there is none. */
    private static String banner(String html) {
        Matcher m = BANNER.matcher(html);
        return m.find() ? m.group() : null;
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
