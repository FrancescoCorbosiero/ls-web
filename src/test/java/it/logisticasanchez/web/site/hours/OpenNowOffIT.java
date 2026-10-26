package it.logisticasanchez.web.site.hours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-18 with {@code site.openNow} off (the default, ADR 0025): the public pages are the M1 ones —
 * the hours without «Aperto ora» and no script for it —, «Impostazioni» has no link to the
 * closures and their routes answer 404.
 */
@Tag("ENH-18")
@SpringBootTest
@AutoConfigureMockMvc
class OpenNowOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private FeatureFlags flags;

    @Test
    void thePublicPagesAndTheSettingsAreTheM1Ones() throws Exception {
        assertThat(flags.isEnabled(FlagKey.SITE_OPEN_NOW)).isFalse();
        for (String path : new String[] {"/", "/contatti/"}) {
            assertThat(page(path, null))
                    .contains("Lun–Ven 8–18 · Sab 8–12")
                    .doesNotContain("data-open-now")
                    .doesNotContain("open-now.js");
        }
        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(page("/gestione/impostazioni/", admin)).doesNotContain("data-closures-link");
        mvc.perform(get("/gestione/impostazioni/chiusure/").session(admin)).andExpect(status().isNotFound());
        mvc.perform(post("/gestione/impostazioni/chiusure/")
                        .session(admin)
                        .with(csrf())
                        .param("dal", "2030-01-02"))
                .andExpect(status().isNotFound());
    }

    private String page(String path, MockHttpSession session) throws Exception {
        var request = get(path);
        if (session != null) {
            request.session(session);
        }
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
