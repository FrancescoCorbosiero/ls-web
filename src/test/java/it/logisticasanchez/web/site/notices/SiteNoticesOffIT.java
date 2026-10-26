package it.logisticasanchez.web.site.notices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.StaffTestData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-17 with {@code site.noticeBanner} off (the default, ADR 0025): the public pages are the M1
 * ones — no banner even with a notice in force left in the table —, «Impostazioni» has no link
 * and the notice routes answer 404.
 */
@Tag("ENH-17")
@SpringBootTest
@AutoConfigureMockMvc
class SiteNoticesOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FeatureFlags flags;

    @Test
    void thePublicPagesAndTheSettingsAreTheM1Ones() throws Exception {
        assertThat(flags.isEnabled(FlagKey.SITE_NOTICE_BANNER)).isFalse();
        StaffUser user = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        long id = jdbc.queryForObject(
                "insert into site_notice (message, created_by) values ('Avviso rimasto acceso', ?) returning id",
                Long.class,
                user.getId());
        MockHttpSession admin = StaffTestData.session(user);

        for (String path : new String[] {"/", "/contatti/"}) {
            assertThat(mvc.perform(get(path))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .doesNotContain("data-site-notice")
                    .doesNotContain("Avviso rimasto acceso");
        }
        assertThat(mvc.perform(get("/gestione/impostazioni/").session(admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-notices-link");
        mvc.perform(get("/gestione/impostazioni/avvisi/").session(admin)).andExpect(status().isNotFound());
        mvc.perform(post("/gestione/impostazioni/avvisi/")
                        .session(admin)
                        .with(csrf())
                        .param("testo", "Nuovo"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/gestione/impostazioni/avvisi/" + id + "/chiudi")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        jdbc.update("delete from site_notice where id = ?", id);
    }
}
