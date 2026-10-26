package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ENH-09 with {@code security.adminTotp} off (the default, ADR 0025): the staff login is the M1
 * one — the password opens the back office even for a user with a stored secret —, the code page
 * and the reset answer 404 and «Utenti della gestione» shows nothing of two-factor.
 */
@Tag("ENH-09")
@SpringBootTest
@AutoConfigureMockMvc
class StaffTwoFactorOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffAccountService accounts;

    @Autowired
    private StaffUserRepository users;

    @Autowired
    private FeatureFlags flags;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theStaffLoginIsTheM1One() throws Exception {
        assertThat(flags.isEnabled(FlagKey.SECURITY_ADMIN_TOTP)).isFalse();
        limiter.reset();
        String username = "spento-" + UUID.randomUUID().toString().substring(0, 8);
        String password = CustomerTestData.newPassword();
        StaffAccountService.SetupLink link = accounts.create(username, "Operatore Spento", null, StaffRole.OPERATOR);
        accounts.setPassword(link.rawToken(), password);
        // a secret left from a time the flag was on is ignored
        jdbc.update(
                "update staff_user set totp_secret = ?, totp_enabled_at = now() where id = ?",
                Totp.newSecret(),
                link.staffUserId());

        MvcResult result = mvc.perform(post("/gestione/login")
                        .param("username", username)
                        .param("password", password)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getAttribute(PendingStaffLogin.SESSION_ATTRIBUTE)).isNull();
        assertThat(mvc.perform(get("/gestione/").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Ciao Operatore Spento");

        mvc.perform(get("/gestione/verifica")).andExpect(status().isNotFound());
        mvc.perform(get("/gestione/verifica").session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/gestione/verifica").session(session).with(csrf()).param("codice", "123456"))
                .andExpect(status().isNotFound());

        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(users, StaffRole.ADMIN));
        assertThat(mvc.perform(get("/gestione/impostazioni/utenti/").session(admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-two-factor")
                .doesNotContain("Verifica in due passaggi");
        mvc.perform(post("/gestione/impostazioni/utenti/" + link.staffUserId() + "/doppia-verifica")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(users.findById(link.staffUserId()).orElseThrow().getTotpSecret())
                .isNotNull();
    }
}
