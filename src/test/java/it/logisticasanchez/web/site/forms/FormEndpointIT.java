package it.logisticasanchez.web.site.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PUB-06, PUB-07, PUB-11, EML-08: {@code POST /api/form} with the server cases of chapter 06 §5.6
 * in JSON and native mode, the honeypot, the unknown form, the field errors, the 20,000-character
 * limit; one valid submission → one {@code lead} row and one EML-08 outbox row (Reply-To = the
 * customer), honeypot → neither; a foreign {@code Origin} is refused (403) and the rate limit
 * answers 429 (ADR 0014). The endpoint is CSRF-exempt: no token is sent.
 */
@Tag("PUB-06")
@Tag("PUB-07")
@Tag("PUB-11")
@Tag("EML-08")
@SpringBootTest
@AutoConfigureMockMvc
class FormEndpointIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RateLimiter rateLimiter;

    @BeforeEach
    void clean() {
        // saved quotes (ENH-20) point to their request: they go first
        jdbc.update("delete from quote_line");
        jdbc.update("delete from quote");
        jdbc.update("delete from lead");
        jdbc.update("delete from email_outbox where kind = 'EML-08'");
        rateLimiter.reset();
    }

    private static MockHttpServletRequestBuilder form(Map<String, String[]> fields, boolean json) {
        MockHttpServletRequestBuilder builder = post("/api/form")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "https://logisticasanchez.com")
                .header("User-Agent", "FormEndpointIT/1.0")
                .with(request -> {
                    request.setRemoteAddr("10.77.0.1");
                    return request;
                });
        if (json) {
            builder.accept(MediaType.APPLICATION_JSON);
        }
        fields.forEach(builder::param);
        return builder;
    }

    private static Map<String, String[]> quote(String field, String value) {
        Map<String, String[]> f = FormValidatorTest.quote();
        FormValidatorTest.put(f, field, value);
        return f;
    }

    private int leads() {
        return jdbc.queryForObject("select count(*) from lead", Integer.class);
    }

    private int mails() {
        return jdbc.queryForObject("select count(*) from email_outbox where kind = 'EML-08'", Integer.class);
    }

    @Test
    void aValidQuoteInJsonModeStoresOneLeadAndQueuesOneEmail() throws Exception {
        mvc.perform(form(FormValidatorTest.quote(), true))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(leads()).isEqualTo(1);
        assertThat(mails()).isEqualTo(1);

        Map<String, Object> lead = jdbc.queryForMap("select * from lead");
        assertThat(lead)
                .containsEntry("form", "PREVENTIVO")
                .containsEntry("status", "NEW")
                .containsEntry("name", "Mario Rossi")
                .containsEntry("company", "Rossi Meccanica S.r.l.")
                .containsEntry("email", "mario.rossi@rossimeccanica.example")
                .containsEntry("phone", "333 000 0000")
                .containsEntry("preferred_contact", "whatsapp")
                .containsEntry("service", "pallet")
                .containsEntry("pickup_postal_code", "20831")
                .containsEntry("pickup_city", "Seregno")
                .containsEntry("delivery_postal_code", "40121")
                .containsEntry("delivery_city", "Bologna")
                .containsEntry("recipient_type", "azienda")
                .containsEntry("pallet_format", "eur")
                .containsEntry("goods_description", "Ricambi meccanici")
                .containsEntry("message", "Ritiro preferibilmente al mattino")
                .containsEntry("user_agent", "FormEndpointIT/1.0");
        assertThat(lead.get("pickup_date").toString()).isEqualTo("2026-10-22");
        assertThat(lead.get("quantity").toString()).startsWith("2");
        assertThat(lead.get("total_weight_kg").toString()).startsWith("650");
        assertThat(lead.get("ip_hash")).isNotNull().asString().hasSize(64).doesNotContain("10.77.0.1");
        assertThat(lead.get("retain_until")).isNotNull();
        assertThat(lead.get("privacy_accepted_at")).isNotNull();
        String extras = jdbc.queryForObject("select array_to_string(extras, '|') from lead", String.class);
        assertThat(extras).isEqualTo("Sponda idraulica|Preavviso al destinatario");
        String payload = jdbc.queryForObject("select payload::text from lead", String.class);
        assertThat(payload)
                .contains("\"form-name\": \"preventivo\"")
                .contains("\"ritiro_cap\": \"20831\"")
                .doesNotContain("bot-field");

        Map<String, Object> mail = jdbc.queryForMap("select * from email_outbox where kind = 'EML-08'");
        assertThat(mail)
                .containsEntry("recipient", "logisticasanchezmb@gmail.com")
                .containsEntry("reply_to", "mario.rossi@rossimeccanica.example")
                .containsEntry(
                        "subject", "Richiesta di preventivo: Spedizione pallet espresso, 20831 Seregno → 40121 Bologna")
                .containsEntry("status", "QUEUED");
        assertThat(mail.get("body_text").toString())
                .startsWith("Nuova richiesta di preventivo\n\nSPEDIZIONE\nServizio: Spedizione pallet espresso")
                .contains("Inviato dal modulo «Preventivo» di logisticasanchez.com il ");
        assertThat(mail.get("body_html").toString()).contains("Scrivi su WhatsApp");
    }

    @Test
    void aValidQuoteWithoutJavaScriptIs303ToGrazie() throws Exception {
        mvc.perform(form(FormValidatorTest.quote(), false))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/grazie/"));
        assertThat(leads()).isEqualTo(1);
        assertThat(mails()).isEqualTo(1);
    }

    @Test
    void aValidContactMessageIsStoredWithItsOwnFields() throws Exception {
        mvc.perform(form(FormValidatorTest.contact(), true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        Map<String, Object> lead = jdbc.queryForMap("select * from lead");
        assertThat(lead)
                .containsEntry("form", "CONTATTI")
                .containsEntry("name", "Giulia Bianchi")
                .containsEntry("subject", "Informazioni su una spedizione")
                .containsEntry(
                        "message", "Buongiorno, vorrei sapere quando passate a ritirare i due pallet ordinati ieri.")
                .containsEntry("phone", null)
                .containsEntry("service", null);
        Map<String, Object> mail = jdbc.queryForMap("select * from email_outbox where kind = 'EML-08'");
        assertThat(mail)
                .containsEntry("subject", "Messaggio dal sito: Informazioni su una spedizione (Giulia Bianchi)")
                .containsEntry("reply_to", "giulia.bianchi@example.it");
    }

    @Test
    void theHoneypotPretendsSuccessAndStoresNothing() throws Exception {
        mvc.perform(form(quote("bot-field", "http://spam.example"), true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(form(quote("bot-field", "x"), false))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/grazie/"));
        assertThat(leads()).isZero();
        assertThat(mails()).isZero();
    }

    @Test
    void theServerCasesOfChapter06InJsonMode() throws Exception {
        expectJson(quote("consegna_cap", "4012"), 422, "invalid:consegna_cap");
        expectJson(quote("consegna_cap", "401234"), 422, "invalid:consegna_cap"); // ADR 0014: refused, not truncated
        expectJson(quote("telefono", ""), 422, "invalid:telefono");
        expectJson(quote("peso", "0"), 422, "invalid:peso");
        expectJson(quote("form-name", "altro"), 400, "unknown_form");
        Map<String, String[]> contact = FormValidatorTest.contact();
        FormValidatorTest.put(contact, "messaggio", "corto");
        expectJson(contact, 422, "invalid:messaggio");
        mvc.perform(form(quote("numero", "2,5"), true)).andExpect(status().isOk()); // quirk kept (PD-04)
        assertThat(leads()).isEqualTo(1);
        assertThat(mails()).isEqualTo(1);
    }

    private void expectJson(Map<String, String[]> fields, int status, String error) throws Exception {
        mvc.perform(form(fields, true))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(error));
    }

    @Test
    void theServerCasesWithoutJavaScriptAreTheInvioNonRiuscitoPage() throws Exception {
        MvcResult result = mvc.perform(form(quote("consegna_cap", "4012"), false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html)
                .contains("<title>Invio non riuscito – Logistica Sanchez</title>")
                .contains("<h1>Invio non riuscito</h1>")
                .contains("Alcuni campi non sono compilati correttamente: torna al modulo e controllali.")
                .contains("Puoi anche scriverci a")
                .contains("href=\"mailto:logisticasanchezmb@gmail.com\"")
                .contains("href=\"/preventivo/\"")
                .contains(">Torna al modulo</a>")
                .contains("<meta name=\"robots\" content=\"noindex\">")
                .doesNotContain(" style=\"");
        html = mvc.perform(form(quote("form-name", "altro"), false))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("Il modulo non è stato riconosciuto.").contains("href=\"/\"");
        assertThat(leads()).isZero();
    }

    @Test
    void bodiesOver20000CharactersAre413() throws Exception {
        String body = "form-name=contatti&nome=" + "a".repeat(20_000);
        mvc.perform(post("/api/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://logisticasanchez.com")
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("too_large"));
    }

    @Test
    void aRequestFromAnotherOriginOrWithoutOneIsRefused() throws Exception {
        MockHttpServletRequestBuilder foreign = post("/api/form")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .header("Origin", "https://evil.example");
        FormValidatorTest.quote().forEach(foreign::param);
        mvc.perform(foreign)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        MockHttpServletRequestBuilder none = post("/api/form")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON);
        FormValidatorTest.quote().forEach(none::param);
        mvc.perform(none).andExpect(status().isForbidden());
        MockHttpServletRequestBuilder foreignReferer = post("/api/form")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Referer", "https://evil.example/pagina");
        FormValidatorTest.quote().forEach(foreignReferer::param);
        mvc.perform(foreignReferer)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        // the request's own origin (localhost, staging) and a same-site Referer are fine
        MockHttpServletRequestBuilder own = post("/api/form")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost");
        FormValidatorTest.quote().forEach(own::param);
        mvc.perform(own).andExpect(status().isOk());
        MockHttpServletRequestBuilder referer = post("/api/form")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .header("Referer", "https://logisticasanchez.com/preventivo/");
        FormValidatorTest.quote().forEach(referer::param);
        mvc.perform(referer).andExpect(status().isOk());
        assertThat(leads()).isEqualTo(2);
    }

    @Test
    void theRateLimitAnswers429PerClientIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(form(FormValidatorTest.contact(), true)).andExpect(status().isOk());
        }
        mvc.perform(form(FormValidatorTest.contact(), true))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("too_many_requests"));
        mvc.perform(form(FormValidatorTest.contact(), false))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        // another client is unaffected
        mvc.perform(form(FormValidatorTest.contact(), true).with(request -> {
                    request.setRemoteAddr("10.77.0.2");
                    return request;
                }))
                .andExpect(status().isOk());
        assertThat(leads()).isEqualTo(11);
    }
}
