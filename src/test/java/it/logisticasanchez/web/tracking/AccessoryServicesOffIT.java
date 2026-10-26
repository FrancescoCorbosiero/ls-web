package it.logisticasanchez.web.tracking;

import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.EXTRAS;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.LEGACY_JSON;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.SITE;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.id;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.quoteExtras;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.siteLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.orders.OrderValidator;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.StaffTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ENH-14 with {@code admin.enumerations} off (the default, ADR 0025): the public pages render their
 * ported lists whatever the table holds, the quote request accepts only the ported extras, the
 * order forms, the order validator and EP-20 know only the six legacy services, and «Impostazioni»
 * has no link to the list, whose routes answer 404 — as in M1.
 */
@Tag("ENH-14")
@SpringBootTest
@AutoConfigureMockMvc
class AccessoryServicesOffIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FeatureFlags flags;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrackingCatalog catalog;

    @Autowired
    private OrderValidator orderValidator;

    @Autowired
    private RateLimiter limiter;

    @AfterEach
    void tearDown() {
        AccessoryServicesTestSupport.restoreSeeds(jdbc);
    }

    @Test
    void thePublicPagesKeepTheirPortedListsWhateverTheTableHolds() throws Exception {
        assertThat(flags.isEnabled(FlagKey.ADMIN_ENUMERATIONS)).isFalse();
        // as if the office had changed the list while the flag was on
        jdbc.update("update accessory_service set label = 'Ritiri su richiesta' where name = 'RITIRI_PROGRAMMATI'");
        jdbc.update("update accessory_service set active = false where name = 'MERCE_FRAGILE'");

        String servizi = page("/servizi/");
        assertThat(servizi).doesNotContain("data-accessories").doesNotContain("th:");
        assertThat(siteLabels(servizi)).containsExactlyElementsOf(SITE);
        String preventivo = page("/preventivo/");
        assertThat(preventivo).doesNotContain("data-accessories").doesNotContain("th:");
        assertThat(quoteExtras(preventivo)).containsExactlyElementsOf(EXTRAS);

        limiter.reset();
        String email = CustomerTestData.uniqueEmail("preventivo-m1");
        mvc.perform(post("/api/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://logisticasanchez.com")
                        .param("form-name", "preventivo")
                        .param("servizio", "pallet")
                        .param("ritiro_cap", "20831")
                        .param("ritiro_localita", "Seregno")
                        .param("consegna_cap", "40121")
                        .param("consegna_localita", "Bologna")
                        .param("destinatario", "azienda")
                        .param("tipo_pallet", "eur")
                        .param("numero", "2")
                        .param("peso", "650")
                        .param("servizi_extra[]", "Ritiri su richiesta", "Merce fragile o non sovrapponibile")
                        .param("nome", "Mario Rossi")
                        .param("email", email)
                        .param("telefono", "333 000 0000")
                        .param("contatto_preferito", "email")
                        .param("privacy", "accettata"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForList("select unnest(extras) from lead where email = ?", String.class, email))
                .as("the extras of the ported wizard, not those of the table")
                .containsExactly("Merce fragile o non sovrapponibile");
    }

    @Test
    void theOrderSideKnowsOnlyTheSixLegacyServices() throws Exception {
        assertThat(catalog.activeServices())
                .extracting(TrackingCatalog.ServiceView::id)
                .containsExactly(0, 1, 2, 3, 4, 5);
        mvc.perform(get("/mono/api/enumeration/servizioAccessorio"))
                .andExpect(content().json(LEGACY_JSON, true));
        assertThat(orderValidator.isService("2")).isTrue();
        assertThat(orderValidator.isService(String.valueOf(id(jdbc, "RITIRI_PROGRAMMATI"))))
                .as("a row of the public lists is not a service of the order")
                .isFalse();
        assertThat(orderValidator.storedServiceId(String.valueOf(id(jdbc, "MERCE_FRAGILE"))))
                .isNull();
    }

    @Test
    void theSettingsAreTheM1Ones() throws Exception {
        MockHttpSession admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        assertThat(mvc.perform(get("/gestione/impostazioni/").session(admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("data-accessories-link")
                .doesNotContain("/gestione/impostazioni/servizi-accessori/");
        String list = "/gestione/impostazioni/servizi-accessori/";
        mvc.perform(get(list).session(admin)).andExpect(status().isNotFound());
        mvc.perform(get(list + "2/").session(admin)).andExpect(status().isNotFound());
        mvc.perform(post(list)
                        .session(admin)
                        .with(csrf())
                        .param("nome", "Servizio di prova")
                        .param("ordini", "si"))
                .andExpect(status().isNotFound());
        mvc.perform(post(list + "8/elimina").session(admin).with(csrf())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from accessory_service", Integer.class))
                .isEqualTo(12);
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
