package it.logisticasanchez.web.site.hours;

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
import java.time.Instant;
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
 * ENH-18 with {@code site.openNow} on (ADR 0042): every public page carries, next to the hours,
 * the openings of the next 14 days (Europe/Rome hours, closure days left out) for
 * {@code /js/open-now.js}; an ADMIN keeps the closure days under «Impostazioni» and the openings
 * follow at once.
 */
@Tag("ENH-18")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Configuration.class)
@TestPropertySource(properties = "ls.flags.override.[site.openNow]=true")
class OpenNowIT extends AbstractPostgresIT {

    private static final String CLOSURES = "/gestione/impostazioni/chiusure/";
    private static final Pattern INTERVALS = Pattern.compile("data-intervals=\"([^\"]*)\"");
    /** Tuesday 17 November 2026, 10:00 in Rome. */
    private static final Instant TUESDAY_TEN = Instant.parse("2026-11-17T09:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private OfficeClosureService closures;

    private StaffUser adminUser;

    @BeforeEach
    void setUp() {
        clock.set(TUESDAY_TEN);
        jdbc.update("delete from office_closure where day >= date '2026-11-01'");
        adminUser = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        closures.evictCache();
    }

    @Test
    void thePublicPagesCarryTheOpeningsOfTheNextDaysForTheScript() throws Exception {
        String page = page(get("/contatti/"));
        assertThat(page)
                .contains("<script type=\"module\" src=\"/js/open-now.js\"></script>")
                .contains("data-open-label=\"Aperto ora\"")
                .contains("data-closed-label=\"Chiuso ora\"");
        String intervals = intervals(page);
        long opensToday = Instant.parse("2026-11-17T07:00:00Z").toEpochMilli();
        long closesToday = Instant.parse("2026-11-17T17:00:00Z").toEpochMilli();
        assertThat(intervals).startsWith("[[" + opensToday + "," + closesToday + "]");
        assertThat(intervals.split("\\],\\[")).as("14 days: 2 Sundays closed").hasSize(12);
        assertThat(page(get("/"))).contains("data-open-now");
        assertThat(page(get("/area-clienti/"))).doesNotContain("data-open-now").doesNotContain("open-now.js");
    }

    @Test
    void aClosureAddedByAnAdminTakesTheDayOutAtOnce() throws Exception {
        assertThat(page(get(CLOSURES).session(admin()))).contains("Adesso il sito scrive: Aperto ora.");
        mvc.perform(post(CLOSURES)
                        .session(admin())
                        .with(csrf())
                        .param("dal", "2026-11-17")
                        .param("motivo", "Inventario"))
                .andExpect(redirectedUrl(CLOSURES))
                .andExpect(flash().attribute("flash", "Chiusura aggiunta: il sito ne tiene conto accanto all'orario."));
        assertThat(intervals(page(get("/contatti/"))))
                .doesNotContain(
                        Long.toString(Instant.parse("2026-11-17T07:00:00Z").toEpochMilli()))
                .startsWith("[[" + Instant.parse("2026-11-18T07:00:00Z").toEpochMilli());
        assertThat(page(get(CLOSURES).session(admin())))
                .contains("Adesso il sito scrive: Chiuso ora.")
                .contains("data-closure=\"2026-11-17\"")
                .contains("Inventario");

        mvc.perform(post(CLOSURES)
                        .session(admin())
                        .with(csrf())
                        .param("dal", "2026-12-24")
                        .param("al", "2026-12-27"))
                .andExpect(redirectedUrl(CLOSURES));
        assertThat(jdbc.queryForObject(
                        "select count(*) from office_closure where day between date '2026-12-24' and date '2026-12-27'",
                        Integer.class))
                .isEqualTo(4);

        mvc.perform(post(CLOSURES + "2026-11-17/rimuovi").session(admin()).with(csrf()))
                .andExpect(redirectedUrl(CLOSURES));
        assertThat(intervals(page(get("/contatti/"))))
                .contains(Long.toString(Instant.parse("2026-11-17T07:00:00Z").toEpochMilli()));
        mvc.perform(post(CLOSURES + "2026-11-17/rimuovi").session(admin()).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action in ('office.closure.add', 'office.closure.remove')"
                                + " and actor_id = ?",
                        Integer.class,
                        adminUser.getId()))
                .isEqualTo(3);
    }

    @Test
    void theFormRefusesPastDaysAndLongOrReversedRanges() throws Exception {
        assertThat(page(post(CLOSURES).session(admin()).with(csrf()).param("dal", "2026-11-16")))
                .contains("Il giorno è già passato.");
        assertThat(page(post(CLOSURES)
                        .session(admin())
                        .with(csrf())
                        .param("dal", "2026-11-20")
                        .param("al", "2026-11-19")))
                .contains("L&#39;ultimo giorno viene dopo il primo.");
        assertThat(page(post(CLOSURES)
                        .session(admin())
                        .with(csrf())
                        .param("dal", "2026-11-20")
                        .param("al", "2027-02-20")))
                .contains("Al massimo 62 giorni alla volta.");
        assertThat(page(post(CLOSURES).session(admin()).with(csrf()).param("dal", "")))
                .contains("Scegli il giorno di chiusura.");
        assertThat(jdbc.queryForObject(
                        "select count(*) from office_closure where day >= date '2026-11-01'", Integer.class))
                .isZero();
        MockHttpSession operator = Sessions.staff(
                new StaffPrincipal(StaffTestData.staff(staffUsers, StaffRole.OPERATOR), clock.instant()),
                "OPERATOR",
                clock.instant());
        mvc.perform(get(CLOSURES).session(operator).accept("text/html")).andExpect(status().isForbidden());
    }

    /** A staff session opened at the test clock's now (the clock is weeks ahead of the real one). */
    private MockHttpSession admin() {
        return Sessions.staff(new StaffPrincipal(adminUser, clock.instant()), "ADMIN", clock.instant());
    }

    private static String intervals(String html) {
        Matcher m = INTERVALS.matcher(html);
        assertThat(m.find()).as("data-intervals").isTrue();
        return m.group(1);
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
