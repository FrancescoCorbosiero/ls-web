package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * ENH-13 with {@code admin.audit} off (the default, ADR 0025): «Impostazioni» has no link to the
 * log and the viewer answers 404, as in M1, where the log is written but not shown.
 */
@Tag("ENH-13")
@SpringBootTest
@AutoConfigureMockMvc
class AuditViewerOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private FeatureFlags flags;

    @Test
    void theSettingsAreTheM1Ones() throws Exception {
        assertThat(flags.isEnabled(FlagKey.ADMIN_AUDIT)).isFalse();
        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(mvc.perform(get("/gestione/impostazioni/").session(admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-audit-link")
                .doesNotContain("/gestione/impostazioni/registro/");
        mvc.perform(get("/gestione/impostazioni/registro/").session(admin)).andExpect(status().isNotFound());
    }
}
