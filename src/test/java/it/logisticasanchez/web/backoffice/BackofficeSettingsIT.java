package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.platform.flags.FeatureFlagService;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * «Impostazioni» (ADMIN only; ADR 0025, 0016, 0003): the home shows the section for an ADMIN and
 * not for an OPERATOR, who gets 403 on every settings route; a flag toggle writes the row and the
 * audit entry; the report recipients allow-list is added to and removed from with audit; a staff
 * account created here gets its set-password link through the outbox (EML-S1) and never a password.
 */
@Tag("ADM-01")
@Tag("ADM-09")
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeSettingsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private FeatureFlagService flags;

    @Autowired
    private JdbcTemplate jdbc;

    private StaffUser admin;
    private MockHttpSession adminSession;
    private MockHttpSession operatorSession;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        admin = StaffTestData.staff(staffUsers, StaffRole.ADMIN);
        adminSession = StaffTestData.session(admin);
        operatorSession = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.OPERATOR));
    }

    /** Leaves the seed exactly as the migrations wrote it (FeatureFlagSeedIT checks «never toggled»). */
    @AfterEach
    void flagsOff() {
        jdbc.update(
                "update feature_flag set enabled = false, updated_by = null where enabled or updated_by is not null");
        flags.evictCache();
    }

    @Test
    void onlyAnAdminReachesTheSettingsAndTheHomeShowsTheSectionAccordingly() throws Exception {
        assertThat(mvc.perform(get("/gestione/").session(adminSession))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("/gestione/impostazioni/")
                .contains("In Approvazione")
                .contains("Registrati")
                .contains("Ordini")
                .contains("Tracking")
                .contains("Report");
        assertThat(mvc.perform(get("/gestione/").session(operatorSession))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("/gestione/impostazioni/");
        for (String path : List.of(
                "/gestione/impostazioni/", "/gestione/impostazioni/utenti/", "/gestione/impostazioni/privacy/")) {
            mvc.perform(get(path).session(operatorSession)).andExpect(status().isForbidden());
            mvc.perform(get(path).session(adminSession)).andExpect(status().isOk());
        }
        mvc.perform(post("/gestione/impostazioni/flag")
                        .session(operatorSession)
                        .with(csrf())
                        .param("chiave", "admin.dashboard")
                        .param("attiva", "1"))
                .andExpect(status().isForbidden());
        assertThat(flags.isEnabled(FlagKey.ofKey("admin.dashboard").orElseThrow()))
                .isFalse();
    }

    @Test
    void anAdminTogglesAFlagAndTheChangeIsAudited() throws Exception {
        FlagKey key = FlagKey.ofKey("admin.dashboard").orElseThrow();
        String page = mvc.perform(get("/gestione/impostazioni/").session(adminSession))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page).contains("data-flag=\"admin.dashboard\"").doesNotContain(">Attiva</span>");
        mvc.perform(post("/gestione/impostazioni/flag")
                        .session(adminSession)
                        .with(csrf())
                        .param("chiave", key.key())
                        .param("attiva", "1"))
                .andExpect(status().is3xxRedirection());
        assertThat(flags.isEnabled(key)).isTrue();
        assertThat(jdbc.queryForObject("select updated_by from feature_flag where key = ?", Long.class, key.key()))
                .isEqualTo(admin.getId());
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'flag.toggle' and actor_id = ? and entity_ref = ?",
                        Long.class,
                        admin.getId(),
                        key.key()))
                .isEqualTo(1);
        mvc.perform(post("/gestione/impostazioni/flag")
                        .session(adminSession)
                        .with(csrf())
                        .param("chiave", key.key())
                        .param("attiva", "0"))
                .andExpect(status().is3xxRedirection());
        assertThat(flags.isEnabled(key)).isFalse();
    }

    @Test
    void theReportRecipientsAllowListIsManagedWithAudit() throws Exception {
        String email = "contabilita-" + UUID.randomUUID().toString().substring(0, 6) + "@ufficio.example";
        mvc.perform(post("/gestione/impostazioni/destinatari")
                        .session(adminSession)
                        .with(csrf())
                        .param("email", " " + email.toUpperCase() + " ")
                        .param("descrizione", "Contabilità"))
                .andExpect(status().is3xxRedirection());
        Long id = jdbc.queryForObject("select id from report_recipient where email = ?", Long.class, email);
        assertThat(id).isNotNull();
        assertThat(mvc.perform(get("/gestione/impostazioni/").session(adminSession))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("data-recipient=\"" + email + "\"")
                .contains("Contabilità");
        String refused = mvc.perform(post("/gestione/impostazioni/destinatari")
                        .session(adminSession)
                        .with(csrf())
                        .param("email", email))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(refused).contains("già tra i destinatari autorizzati");
        assertThat(mvc.perform(post("/gestione/impostazioni/destinatari")
                                .session(adminSession)
                                .with(csrf())
                                .param("email", "non-un-indirizzo"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("indirizzo e-mail valido");
        mvc.perform(post("/gestione/impostazioni/destinatari/" + id + "/rimuovi")
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select count(*) from report_recipient where id = ?", Long.class, id))
                .isEqualTo(0);
        assertThat(jdbc.queryForList(
                        "select action from audit_log where entity_type = 'report_recipient' and entity_id = ? order by id",
                        String.class,
                        id))
                .containsExactly("report.recipient.add", "report.recipient.remove");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where entity_type = 'report_recipient' and (before::text like ? or after::text like ?)",
                        Long.class,
                        "%" + email + "%",
                        "%" + email + "%"))
                .as("the address is in the detail, never in the log row")
                .isEqualTo(0);
    }

    @Test
    void anAdminCreatesAStaffAccountAndTheSetPasswordLinkGoesThroughTheOutbox() throws Exception {
        String username = "nuovo-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@ufficio.example";
        String form = mvc.perform(get("/gestione/impostazioni/utenti/").session(adminSession))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(form).contains("data-user=\"" + admin.getUsername() + "\"").doesNotContain("type=\"password\"");
        mvc.perform(post("/gestione/impostazioni/utenti/")
                        .session(adminSession)
                        .with(csrf())
                        .param("username", username.toUpperCase())
                        .param("nome", "Nuovo Operatore")
                        .param("email", email.toUpperCase())
                        .param("ruolo", "OPERATOR"))
                .andExpect(status().is3xxRedirection());
        StaffUser created = staffUsers.findByUsername(username).orElseThrow();
        assertThat(created.getRole()).isEqualTo(StaffRole.OPERATOR);
        assertThat(created.getPasswordHash()).isNull();
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-S1");
        assertThat(mails.get(0).getRecipient()).isEqualTo(email);
        assertThat(mails.get(0).getTokenCiphertext()).isNotNull();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'staff.create' and actor_id = ? and entity_id = ?",
                        Long.class,
                        admin.getId(),
                        created.getId()))
                .isEqualTo(1);
        String duplicate = mvc.perform(post("/gestione/impostazioni/utenti/")
                        .session(adminSession)
                        .with(csrf())
                        .param("username", username)
                        .param("nome", "Doppione")
                        .param("email", "doppione@ufficio.example")
                        .param("ruolo", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(duplicate).contains("Esiste già un utente con questo username");
        assertThat(mvc.perform(post("/gestione/impostazioni/utenti/")
                                .session(adminSession)
                                .with(csrf())
                                .param("username", "altro-" + username)
                                .param("nome", "Stessa Mail")
                                .param("email", email)
                                .param("ruolo", "OPERATOR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("Esiste già un utente con questa e-mail");
        assertThat(mvc.perform(post("/gestione/impostazioni/utenti/")
                                .session(adminSession)
                                .with(csrf())
                                .param("username", "senza-mail")
                                .param("nome", "Senza Mail")
                                .param("email", "")
                                .param("ruolo", "OPERATOR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("indirizzo e-mail valido");
        assertThat(mvc.perform(get("/gestione/impostazioni/utenti/").session(adminSession))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("data-user=\"" + username + "\"")
                .contains("In attesa della password");
    }
}
