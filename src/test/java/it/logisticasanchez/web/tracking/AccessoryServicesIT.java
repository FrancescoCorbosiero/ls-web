package it.logisticasanchez.web.tracking;

import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.EXTRAS;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.LEGACY_JSON;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.SITE;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.id;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.orderChoices;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.quoteExtras;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.siteIcons;
import static it.logisticasanchez.web.tracking.AccessoryServicesTestSupport.siteLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ENH-14 with {@code admin.enumerations} on (ADR 0043): the seeded list draws the same «Servizi
 * accessori» of {@code /servizi/} and the same wizard extras as the ported pages; an ADMIN adds a
 * service that shows where chosen — order forms, EP-20 with the next id and a code of its own,
 * {@code /servizi/}, the wizard, whose request accepts it —, moves, edits, switches off and
 * deletes it; the legacy rows keep id, code and place on the order, item 0 is never touched, a
 * service in use is not deleted and stays on the orders that have it; the form refuses what
 * could not be shown; an OPERATOR is refused.
 */
@Tag("ENH-14")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ls.flags.override.[admin.enumerations]=true")
class AccessoryServicesIT extends AbstractPostgresIT {

    private static final String LIST = "/gestione/impostazioni/servizi-accessori/";
    private static final String ACCESSORIES_EP = "/mono/api/enumeration/servizioAccessorio";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderService orders;

    @Autowired
    private RateLimiter limiter;

    private MockHttpSession admin;
    private CustomerTestData.Ids customer;

    @BeforeEach
    void setUp() {
        AccessoryServicesTestSupport.restoreSeeds(jdbc);
        limiter.reset();
        admin = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.ADMIN));
        customer = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("servizi"), "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        // the other classes read the seeded enumeration (API-02, ADM-10)
        AccessoryServicesTestSupport.restoreSeeds(jdbc);
    }

    @Test
    void theSeededListDrawsTheSameListsAsThePortedPages() throws Exception {
        String servizi = page(get("/servizi/"));
        assertThat(servizi).contains("data-accessories");
        assertThat(siteLabels(servizi)).containsExactlyElementsOf(SITE);
        assertThat(siteIcons(servizi))
                .containsExactly(
                        "i-arrow-down-to-line",
                        "i-calendar-clock",
                        "i-phone-call",
                        "i-repeat",
                        "i-file-check",
                        "i-shield-check");
        assertThat(servizi)
                .contains("Il pallet viene calato a terra con la pedana del mezzo")
                .contains("previsti per legge.</p>");

        String preventivo = page(get("/preventivo/"));
        assertThat(preventivo).contains("data-accessories");
        assertThat(quoteExtras(preventivo)).containsExactlyElementsOf(EXTRAS);
        assertThat(preventivo)
                .contains("<small>A destinazione non c&#39;è un muletto</small>")
                .contains("<small>Viaggia senza altri carichi sopra</small>");

        mvc.perform(get(ACCESSORIES_EP)).andExpect(content().json(LEGACY_JSON, true));
        assertThat(orderChoices(page(get("/area-clienti/ordini/nuovo/").session(CustomerTestData.session(customer)))))
                .containsExactly(
                        "Nessun servizio accessorio",
                        "Prenotazione telefonica",
                        "Sponda idraulica",
                        "Segnalazione merce lunga",
                        "Facchinaggio",
                        "GDO");

        assertThat(page(get("/gestione/impostazioni/").session(admin))).contains("data-accessories-link");
        String list = page(get(LIST).session(admin));
        assertThat(list)
                .contains("data-service=\"NESSUN_SERVIZIO\"")
                .contains("data-service=\"MERCE_FRAGILE\"")
                .contains("Sempre la prima scelta dell&#39;ordine")
                .contains("Codice SPONDA_IDR");
    }

    @Test
    void anAdminAddsAServiceThatShowsWhereChosen() throws Exception {
        mvc.perform(post(LIST)
                        .session(admin)
                        .with(csrf())
                        .param("nome", "  Consegna al piano  ")
                        .param("ordini", "si")
                        .param("sito", "si")
                        .param("preventivo", "si")
                        .param(
                                "descrizione",
                                "Portiamo la merce fino al piano del destinatario, con o senza ascensore.")
                        .param("icona", "i-boxes")
                        .param("suggerimento", "Fino alla porta del destinatario"))
                .andExpect(redirectedUrl(LIST))
                .andExpect(flash().attribute("flash", "Servizio «Consegna al piano» aggiunto in fondo all'elenco."));
        int id = id(jdbc, "CONSEGNA_AL_PIANO");
        assertThat(id).as("after the legacy ids and the V023 rows").isGreaterThan(11);

        String servizi = page(get("/servizi/"));
        assertThat(siteLabels(servizi)).endsWith("Consegna al piano");
        assertThat(siteIcons(servizi)).endsWith("i-boxes");
        assertThat(servizi).contains("Portiamo la merce fino al piano del destinatario");
        assertThat(quoteExtras(page(get("/preventivo/")))).endsWith("Consegna al piano");
        assertThat(orderChoices(page(get("/area-clienti/ordini/nuovo/").session(CustomerTestData.session(customer)))))
                .endsWith("GDO", "Consegna al piano");
        mvc.perform(get(ACCESSORIES_EP))
                .andExpect(content()
                        .json(
                                LEGACY_JSON.replace(
                                        "]",
                                        ",{\"id\":" + id
                                                + ",\"name\":\"CONSEGNA_AL_PIANO\",\"label\":\"Consegna al piano\"}]"),
                                true));
        assertThat(quoteRequest("Consegna al piano", "Ritiro con la gru"))
                .as("the request keeps the new extra and drops one that is not offered")
                .containsExactly("Consegna al piano");

        mvc.perform(post(LIST + id + "/sposta").session(admin).with(csrf()).param("verso", "su"))
                .andExpect(redirectedUrl(LIST));
        assertThat(quoteExtras(page(get("/preventivo/"))))
                .as("one order for every place")
                .endsWith("Consegna al piano", "Merce fragile o non sovrapponibile");

        mvc.perform(post(LIST + id + "/")
                        .session(admin)
                        .with(csrf())
                        .param("nome", "Consegna al piano o in cantina")
                        .param("sito", "si")
                        .param("descrizione", "Portiamo la merce fino al piano o in cantina.")
                        .param("icona", "i-boxes"))
                .andExpect(redirectedUrl(LIST));
        assertThat(jdbc.queryForObject("select name from accessory_service where id = ?", String.class, id))
                .as("the code never changes")
                .isEqualTo("CONSEGNA_AL_PIANO");
        assertThat(siteLabels(page(get("/servizi/")))).contains("Consegna al piano o in cantina");
        assertThat(quoteExtras(page(get("/preventivo/")))).containsExactlyElementsOf(EXTRAS);
        mvc.perform(get(ACCESSORIES_EP)).andExpect(content().json(LEGACY_JSON, true));

        mvc.perform(post(LIST + id + "/stato").session(admin).with(csrf()).param("attivo", "no"))
                .andExpect(redirectedUrl(LIST));
        assertThat(siteLabels(page(get("/servizi/")))).containsExactlyElementsOf(SITE);
        assertThat(page(get(LIST).session(admin))).contains("Riattiva «Consegna al piano o in cantina»");

        mvc.perform(post(LIST + id + "/elimina").session(admin).with(csrf()))
                .andExpect(redirectedUrl(LIST))
                .andExpect(flash().attribute("flash", "«Consegna al piano o in cantina» eliminato."));
        assertThat(jdbc.queryForObject("select count(*) from accessory_service where id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForList(
                        "select action from audit_log where entity_type = 'accessory_service' and entity_id = ?"
                                + " order by id",
                        String.class,
                        (long) id))
                .containsExactly(
                        "accessory.service.create",
                        "accessory.service.move",
                        "accessory.service.update",
                        "accessory.service.deactivate",
                        "accessory.service.delete");
    }

    @Test
    void theLegacyRowsKeepTheirPlaceAndItemZeroIsNeverTouched() throws Exception {
        for (String path : new String[] {"0/", "0/sposta", "0/stato", "0/elimina"}) {
            mvc.perform(post(LIST + path)
                            .session(admin)
                            .with(csrf())
                            .param("verso", "su")
                            .param("attivo", "no"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(LIST + "0/").session(admin)).andExpect(status().isNotFound());
        mvc.perform(get(LIST + "x/").session(admin)).andExpect(status().isNotFound());

        String legacyForm = page(get(LIST + "2/").session(admin));
        assertThat(legacyForm).contains("È uno dei servizi storici dell&#39;ordine");
        Matcher onTheOrder = Pattern.compile("<input type=\"checkbox\" name=\"ordini\"[^>]*>")
                .matcher(legacyForm);
        assertThat(onTheOrder.find()).isTrue();
        assertThat(onTheOrder.group()).contains("checked=\"checked\"").contains("disabled=\"disabled\"");
        mvc.perform(post(LIST + "2/")
                        .session(admin)
                        .with(csrf())
                        .param("nome", "Sponda idraulica elettrica")
                        .param("sito", "si")
                        .param("descrizione", "Il pallet scende a terra con la pedana elettrica del mezzo.")
                        .param("icona", "i-arrow-down-to-line"))
                .andExpect(redirectedUrl(LIST));
        assertThat(jdbc.queryForMap("select name, in_orders, in_quote from accessory_service where id = 2"))
                .as("still on the order, with its code; no longer in the wizard")
                .containsEntry("name", "SPONDA_IDR")
                .containsEntry("in_orders", true)
                .containsEntry("in_quote", false);
        mvc.perform(get(ACCESSORIES_EP))
                .andExpect(content()
                        .json(LEGACY_JSON.replace("\"Sponda idraulica\"", "\"Sponda idraulica elettrica\""), true));
        assertThat(siteLabels(page(get("/servizi/")))).startsWith("Sponda idraulica elettrica");
        assertThat(quoteExtras(page(get("/preventivo/")))).doesNotContain("Sponda idraulica");
        mvc.perform(post(LIST + "2/elimina").session(admin).with(csrf())).andExpect(status().isNotFound());

        mvc.perform(post(LIST + "1/stato").session(admin).with(csrf()).param("attivo", "no"))
                .andExpect(redirectedUrl(LIST));
        assertThat(orderChoices(page(get("/area-clienti/ordini/nuovo/").session(CustomerTestData.session(customer)))))
                .doesNotContain("Prenotazione telefonica");
        mvc.perform(get(ACCESSORIES_EP))
                .andExpect(content()
                        .json(LEGACY_JSON.replace("\"Sponda idraulica\"", "\"Sponda idraulica elettrica\""), true));
        mvc.perform(post(LIST + "1/sposta").session(admin).with(csrf()).param("verso", "su"))
                .andExpect(redirectedUrl(LIST));
        assertThat(jdbc.queryForObject("select sort_order from accessory_service where id = 1", Integer.class))
                .as("item 0 stays first")
                .isEqualTo(1);
    }

    @Test
    void aServiceInUseIsNotDeletedAndStaysOnItsOrders() throws Exception {
        mvc.perform(post(LIST)
                        .session(admin)
                        .with(csrf())
                        .param("nome", "Scarico con transpallet")
                        .param("ordini", "si"))
                .andExpect(redirectedUrl(LIST));
        int id = id(jdbc, "SCARICO_CON_TRANSPALLET");
        OrderDraft draft = OrderTestData.draft("DDT-TEST-0141", "Destinatario Prova S.r.l.", "Monza", "20900");
        draft.setDetails("DDT-TEST-0141", "3", id, "Consegna al mattino");
        OrderView order = orders.submit(customer.customerId(), customer.customerUserId(), draft);

        mvc.perform(post(LIST + id + "/elimina").session(admin).with(csrf()))
                .andExpect(redirectedUrl(LIST))
                .andExpect(flash().attribute(
                                "flashError",
                                "«Scarico con transpallet» è in ordini o preventivi già registrati: non si può eliminare,"
                                        + " puoi disattivarlo."));
        assertThat(jdbc.queryForObject("select count(*) from accessory_service where id = ?", Integer.class, id))
                .isOne();

        mvc.perform(post(LIST + id + "/stato").session(admin).with(csrf()).param("attivo", "no"))
                .andExpect(redirectedUrl(LIST));
        MockHttpSession session = CustomerTestData.session(customer);
        assertThat(orderChoices(page(get("/area-clienti/ordini/nuovo/").session(session))))
                .doesNotContain("Scarico con transpallet");
        assertThat(orderChoices(page(get("/area-clienti/ordini/" + order.displayCode() + "/modifica")
                        .session(session))))
                .as("an order that has it keeps it while it is edited")
                .contains("Scarico con transpallet");
    }

    @Test
    void theFormRefusesWhatCouldNotBeShownAndOnlyAnAdminUsesIt() throws Exception {
        assertThat(refused(new String[] {"nome", ""}, new String[] {"ordini", "si"}))
                .contains("Scrivi il nome del servizio.");
        assertThat(refused(new String[] {"nome", "Servizio senza posto"}))
                .contains("Scegli almeno un posto in cui compare.");
        assertThat(refused(new String[] {"nome", "Servizio senza testo"}, new String[] {"sito", "si"}))
                .contains("Scrivi la descrizione che compare sul sito.")
                .contains("Scegli l&#39;icona che compare sul sito.");
        assertThat(refused(
                        new String[] {"nome", "Servizio con icona strana"},
                        new String[] {"sito", "si"},
                        new String[] {"descrizione", "Testo di prova."},
                        new String[] {"icona", "i-inventata"}))
                .contains("Scegli un&#39;icona dell&#39;elenco.");
        assertThat(refused(new String[] {"nome", "SPONDA IDRAULICA"}, new String[] {"ordini", "si"}))
                .contains("C&#39;è già un servizio con questo nome.");
        assertThat(refused(
                        new String[] {"nome", "Servizio lungo"},
                        new String[] {"ordini", "si"},
                        new String[] {"descrizione", "x".repeat(301)},
                        new String[] {"suggerimento", "y".repeat(121)}))
                .contains("La descrizione può avere al massimo 300 caratteri.")
                .contains("Il suggerimento può avere al massimo 120 caratteri.");
        assertThat(jdbc.queryForObject("select count(*) from accessory_service", Integer.class))
                .isEqualTo(12);

        mvc.perform(post(LIST + "8/sposta").session(admin).with(csrf()).param("verso", "di lato"))
                .andExpect(status().isBadRequest());
        MockHttpSession operator = StaffTestData.session(StaffTestData.staff(staffUsers, StaffRole.OPERATOR));
        mvc.perform(get(LIST).session(operator).accept("text/html")).andExpect(status().isForbidden());
        mvc.perform(get(LIST).accept("text/html")).andExpect(status().isFound());
    }

    private String refused(String[]... fields) throws Exception {
        MockHttpServletRequestBuilder request = post(LIST).session(admin).with(csrf());
        for (String[] field : fields) {
            request.param(field[0], field[1]);
        }
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).contains("data-form-errors");
        return body;
    }

    /** Sends a quote request with these extras and returns the extras of the stored request. */
    private List<String> quoteRequest(String... extras) throws Exception {
        String email = CustomerTestData.uniqueEmail("preventivo");
        mvc.perform(post("/api/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://logisticasanchez.com")
                        .param("form-name", "preventivo")
                        .param("bot-field", "")
                        .param("servizio", "pallet")
                        .param("ritiro_cap", "20831")
                        .param("ritiro_localita", "Seregno")
                        .param("consegna_cap", "40121")
                        .param("consegna_localita", "Bologna")
                        .param("destinatario", "azienda")
                        .param("tipo_pallet", "eur")
                        .param("numero", "2")
                        .param("peso", "650")
                        .param("servizi_extra[]", extras)
                        .param("nome", "Mario Rossi")
                        .param("email", email)
                        .param("telefono", "333 000 0000")
                        .param("contatto_preferito", "email")
                        .param("privacy", "accettata"))
                .andExpect(status().isOk());
        return jdbc.queryForList("select unnest(extras) from lead where email = ?", String.class, email);
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
