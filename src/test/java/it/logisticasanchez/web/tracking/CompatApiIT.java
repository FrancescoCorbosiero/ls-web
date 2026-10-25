package it.logisticasanchez.web.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.ratelimit.RateLimiter;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The compatibility API against synthetic orders written by SQL (the {@code orders} entities are
 * Phase 2). API-01 with the fixtures FX-01…FX-10 of chapter 02 §10.4 on both hosts (TRK-02 matching,
 * TRK-03 allow-list, TRK-04 rate limit, CORS of Q-39); API-02 with FX-11/FX-12 and the ADM-10 seeds;
 * API-03 with FX-13/FX-14, the retired mappings of {@code legacy-ls-api-security.md} §5, the Italian
 * page behind the old e-mail links and the logo (ADR 0010, ADR 0011).
 */
@Tag("API-01")
@Tag("API-02")
@Tag("API-03")
@Tag("ADM-10")
@Tag("TRK-02")
@Tag("TRK-03")
@Tag("TRK-04")
@SpringBootTest
@AutoConfigureMockMvc
class CompatApiIT extends AbstractPostgresIT {

    private static final String APEX = "logisticasanchez.com";
    private static final String APP = "app.logisticasanchez.com";
    private static final Instant UPDATED = Instant.parse("2026-10-20T08:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RateLimiter rateLimiter;

    private long customerId;
    private String suffix;

    @BeforeEach
    void orders() {
        rateLimiter.reset();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        customerId = jdbc.queryForObject(
                "insert into customer (email, company_name, address_line, city, province, postal_code, phone, status,"
                        + " email_verified_at, approved_at, source) values (?, 'Tracking Test S.r.l.', 'Via di Prova 1',"
                        + " 'Seregno', 'MB', '20831', '+39 333 000 0000', 'ACTIVE', now(), now(), 'BACKOFFICE') returning id",
                Long.class,
                "tracking-" + suffix + "@cliente.example");
        // FX-01/FX-02: one approved order per state, codes LS-2024-0118 (PRESA) and LS-TEST-<state>
        approved("LS-2024-0118", 1, UPDATED);
        approved("LS-TEST-0", 0, null);
        approved("LS-TEST-2", 2, UPDATED);
        approved("LS-TEST-3", 3, UPDATED);
        approved("LS-TEST-4", 4, UPDATED);
        approved("LS-TEST-5", 5, UPDATED);
        approved("Codice Con Spazi", 2, UPDATED);
        // FX-06: a pending order with its temporary code; a declined order with a code
        order("PENDING", "TEMP-PEND" + suffix, null, 0, null);
        order("DECLINED", "TEMP-DECL" + suffix, "LS-DECLINED", 0, null);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from shipment_order where customer_id = ?", customerId);
        jdbc.update("delete from customer where id = ?", customerId);
    }

    private void approved(String code, int state, Instant updatedAt) {
        order("APPROVED", "TEMP-" + UUID.randomUUID(), code, state, updatedAt);
    }

    private void order(String status, String tempCode, String code, int state, Instant updatedAt) {
        jdbc.update(
                "insert into shipment_order (customer_id, status, temp_code, code, transport_document_code, amount,"
                        + " total_weight_kg, total_volume_m3, tracking_state_id, tracking_updated_at, sender_name,"
                        + " sender_address_line, sender_city, sender_province, sender_postal_code, sender_email,"
                        + " recipient_name, recipient_address_line, recipient_city, recipient_province,"
                        + " recipient_postal_code, approved_at, declined_at, source) values (?, ?, ?, ?, 'DDT-1', ?, 100, 1,"
                        + " ?, ?, 'Tracking Test S.r.l.', 'Via di Prova 1', 'Seregno', 'MB', '20831',"
                        + " 'tracking@cliente.example', 'Destinatario Test', 'Via Esempio 2', 'Bologna', 'BO', '40121',"
                        + " ?, ?, 'CUSTOMER_AREA')",
                customerId,
                status,
                tempCode,
                code,
                "APPROVED".equals(status) ? 120.50 : null,
                state,
                updatedAt == null ? null : Timestamp.from(updatedAt),
                "APPROVED".equals(status) ? Timestamp.from(UPDATED) : null,
                "DECLINED".equals(status) ? Timestamp.from(UPDATED) : null);
    }

    private static MockHttpServletRequestBuilder trace(String host, String json) {
        return post(CompatApiController.TRACE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(request -> {
                    request.setServerName(host);
                    request.addHeader("Host", host);
                    request.setRemoteAddr("10.66.0.1");
                    return request;
                });
    }

    // ---- API-01 (FX-01…FX-10)

    @ParameterizedTest(name = "FX-01 on {0}")
    @ValueSource(strings = {APEX, APP, "localhost"})
    void fx01AKnownCodeAnswersItsLabel(String host) throws Exception {
        mvc.perform(trace(host, "{\"codice\":\"LS-2024-0118\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("Presa in carico"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-20T08:30:00Z"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest(name = "FX-02 {0} → {1}")
    @CsvSource({
        "LS-TEST-0, Non gestito",
        "LS-2024-0118, Presa in carico",
        "LS-TEST-2, Transito",
        "LS-TEST-3, Terminal destinatario",
        "LS-TEST-4, Assegnato",
        "LS-TEST-5, Consegnato"
    })
    void fx02EveryStateAnswersItsLegacyLabel(String code, String label) throws Exception {
        mvc.perform(trace(APEX, "{\"codice\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(label));
    }

    @ParameterizedTest(name = "FX-03/FX-06 {0} → Non gestito")
    @ValueSource(strings = {"NONESISTE", "LS-DECLINED", "LS-2024-0119"})
    void fx03UnknownAndDeclinedCodesAnswerNonGestito(String code) throws Exception {
        mvc.perform(trace(APEX, "{\"codice\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"Non gestito\"}", true));
    }

    @Test
    void fx06TheTemporaryCodeOfAPendingOrderAnswersNonGestito() throws Exception {
        mvc.perform(trace(APEX, "{\"codice\":\"TEMP-PEND" + suffix + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"Non gestito\"}", true));
        mvc.perform(trace(APEX, "{\"codice\":\"TEMP-DECL" + suffix + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"Non gestito\"}", true));
    }

    @ParameterizedTest(name = "FX-04 {0}")
    @ValueSource(
            strings = {"  ls-2024-0118 ", "LS-2024-0118 ", " LS - 2024 - 0118", "codiceconspazi", "CODICE CON SPAZI"})
    void fx04PaddedLowerCaseAndSpacedCodesMatch(String code) throws Exception {
        String label = code.toLowerCase().replace(" ", "").contains("spazi") ? "Transito" : "Presa in carico";
        mvc.perform(trace(APEX, "{\"codice\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(label));
    }

    @ParameterizedTest(name = "FX-05 {0}")
    @ValueSource(strings = {"{}", "{\"codice\":\"\"}", "{\"codice\":\"   \"}", "{\"codice\":null}", "{\"codice\":12}"})
    void fx05AnEmptyOrMissingCodeIs400(String json) throws Exception {
        mvc.perform(trace(APEX, json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("bad_request"));
        mvc.perform(post(CompatApiController.TRACE_PATH)).andExpect(status().isBadRequest());
        mvc.perform(trace(APEX, "{not json")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownFieldsAreIgnoredAndTheAnswerHasOnlyTheAllowedKeys() throws Exception {
        MvcResult result = mvc.perform(trace(APEX, "{\"codice\":\"LS-2024-0118\",\"extra\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"status\":\"Presa in carico\",\"updatedAt\":\"2026-10-20T08:30:00Z\"}");
        // FX-10 / TRK-03: no personal data, no documents
        for (String code : List.of("LS-2024-0118", "LS-TEST-5", "LS-TEST-0", "NONESISTE")) {
            String body = mvc.perform(trace(APEX, "{\"codice\":\"" + code + "\"}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body)
                    .doesNotContain("Destinatario")
                    .doesNotContain("Tracking Test")
                    .doesNotContain("Via ")
                    .doesNotContain("@")
                    .doesNotContain("DDT")
                    .doesNotContain("pod")
                    .doesNotContain("120.5");
            assertThat(body.replaceAll("\"(status|updatedAt)\":\"[^\"]*\",?", ""))
                    .isEqualTo("{}");
        }
    }

    @Test
    void fx07PreflightFromTheApexIsAllowedWithoutCredentials() throws Exception {
        mvc.perform(options(CompatApiController.TRACE_PATH)
                        .header("Origin", "https://logisticasanchez.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://logisticasanchez.com"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET, POST, OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(trace(APP, "{\"codice\":\"LS-2024-0118\"}").header("Origin", "https://logisticasanchez.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://logisticasanchez.com"))
                .andExpect(header().string("Vary", "Origin"));
    }

    @ParameterizedTest(name = "FX-08 {0}")
    @ValueSource(strings = {"https://example.org", "https://ordini.logisticasanchez.com", "null"})
    void fx08PreflightFromAnotherOriginGetsNoAllowOrigin(String origin) throws Exception {
        MvcResult result = mvc.perform(options(CompatApiController.TRACE_PATH)
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isNull();
        result = mvc.perform(trace(APEX, "{\"codice\":\"LS-2024-0118\"}").header("Origin", origin))
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                .isNull();
    }

    @Test
    void fx09MoreLookupsThanTheLimitAre429ForThatClientOnly() throws Exception {
        for (int i = 0; i < 60; i++) {
            mvc.perform(trace(APEX, "{\"codice\":\"LS-2024-0118\"}")).andExpect(status().isOk());
        }
        mvc.perform(trace(APEX, "{\"codice\":\"LS-2024-0118\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("too_many_requests"));
        mvc.perform(trace(APEX, "{\"codice\":\"LS-2024-0118\"}").with(request -> {
                    request.setRemoteAddr("10.66.0.2");
                    return request;
                }))
                .andExpect(status().isOk());
    }

    // ---- API-02 (FX-11, FX-12) and ADM-10

    @Test
    void fx11TheTrackingStatesAreTheLegacyArray() throws Exception {
        for (String host : List.of(APEX, APP)) {
            mvc.perform(on(host, CompatApiController.TRACKING_STATES_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content()
                            .json(
                                    "[{\"id\":0,\"name\":\"NON_GESTITO\",\"label\":\"Non gestito\"},"
                                            + "{\"id\":1,\"name\":\"PRESA\",\"label\":\"Presa in carico\"},"
                                            + "{\"id\":2,\"name\":\"TRANSITO\",\"label\":\"Transito\"},"
                                            + "{\"id\":3,\"name\":\"TERMINAL_DEST\",\"label\":\"Terminal destinatario\"},"
                                            + "{\"id\":4,\"name\":\"ASSEGNATO\",\"label\":\"Assegnato\"},"
                                            + "{\"id\":5,\"name\":\"CONSEGNATO\",\"label\":\"Consegnato\"}]",
                                    true));
        }
        String body = mvc.perform(get(CompatApiController.TRACKING_STATES_PATH))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).startsWith("[{\"id\":0,\"name\":\"NON_GESTITO\"");
    }

    @Test
    void fx12TheAccessoryServicesAreTheLegacyArray() throws Exception {
        mvc.perform(get(CompatApiController.ACCESSORY_SERVICES_PATH))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json(
                                "[{\"id\":0,\"name\":\"NESSUN_SERVIZIO\",\"label\":\"Nessun servizio accessorio\"},"
                                        + "{\"id\":1,\"name\":\"PRENOTAZIONE_TEL\",\"label\":\"Prenotazione telefonica\"},"
                                        + "{\"id\":2,\"name\":\"SPONDA_IDR\",\"label\":\"Sponda idraulica\"},"
                                        + "{\"id\":3,\"name\":\"MERCE_LUNGA\",\"label\":\"Segnalazione merce lunga\"},"
                                        + "{\"id\":4,\"name\":\"FACCHINAGGIO\",\"label\":\"Facchinaggio\"},"
                                        + "{\"id\":5,\"name\":\"GDO\",\"label\":\"GDO\"}]",
                                true));
        mvc.perform(options(CompatApiController.ACCESSORY_SERVICES_PATH)
                        .header("Origin", "https://logisticasanchez.com")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://logisticasanchez.com"));
    }

    // ---- API-03 (FX-13, FX-14 and the retired mappings)

    @ParameterizedTest(name = "410 {0} {1}")
    @CsvSource({
        "POST, /mono/auth/login",
        "POST, /mono/auth/refresh-token",
        "POST, /mono/api/customer/state",
        "POST, /mono/api/customer/register",
        "POST, /mono/api/customer/pending",
        "POST, /mono/api/customer/registered",
        "POST, /mono/api/order/state",
        "POST, /mono/api/order/register",
        "POST, /mono/api/order/pending",
        "POST, /mono/api/order/registered",
        "POST, /mono/api/order/active",
        "POST, /mono/api/order/delivered",
        "POST, /mono/api/order/detail",
        "GET, /mono/api/enumeration/tipoPallet",
        "GET, /mono/api/enumeration/statoConferma",
        "POST, /mono/sendEmail",
        "POST, /mono/cms/customer/confirm",
        "POST, /mono/cms/customer/decline",
        "POST, /mono/cms/order/confirm",
        "POST, /mono/cms/order/decline",
        "POST, /mono/cms/order/report",
        "POST, /mono/cms/order/trace",
        "POST, /mono/cms/notes",
        "POST, /mono/cms/notes/update",
        "POST, /mono/cms/order",
        "POST, /mono/cms/order/update",
        "POST, /mono/utils/order/delete",
        "POST, /mono/utils/order/deleteAll",
        "POST, /mono/utils/order/deleteBeforeDate",
        "POST, /mono/utils/customer/delete",
        "GET, /mono/api/customer/state",
        "PUT, /mono/api/order/register",
        "GET, /mono/qualcosa/di/nuovo"
    })
    void retiredEndpointsAnswer410JsonAndWriteNothing(String method, String path) throws Exception {
        int ordersBefore = jdbc.queryForObject("select count(*) from shipment_order", Integer.class);
        int customersBefore = jdbc.queryForObject("select count(*) from customer", Integer.class);
        int mailsBefore = jdbc.queryForObject("select count(*) from email_outbox", Integer.class);
        for (String host : List.of(APEX, APP)) {
            MvcResult result = mvc.perform(request(HttpMethod.valueOf(method), path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"qualcuno@cliente.example\",\"codice\":\"LS-2024-0118\"}")
                            .header("Origin", "https://logisticasanchez.com")
                            .with(r -> {
                                r.setServerName(host);
                                r.addHeader("Host", host);
                                return r;
                            }))
                    .andExpect(status().isGone())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(410))
                    .andExpect(jsonPath("$.code").value("gone"))
                    .andExpect(jsonPath("$.detail")
                            .value("Servizio non più disponibile: usa https://logisticasanchez.com/area-clienti/"))
                    .andReturn();
            assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                    .isNull();
            assertThat(result.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow");
        }
        assertThat(jdbc.queryForObject("select count(*) from shipment_order", Integer.class))
                .isEqualTo(ordersBefore);
        assertThat(jdbc.queryForObject("select count(*) from customer", Integer.class))
                .isEqualTo(customersBefore);
        assertThat(jdbc.queryForObject("select count(*) from email_outbox", Integer.class))
                .isEqualTo(mailsBefore);
    }

    @ParameterizedTest(name = "old link {0}")
    @ValueSource(
            strings = {
                "/mono/email-confirm?email=qualcuno@cliente.example&verificationCode=abc",
                "/mono/email-confirm?email=inesistente@cliente.example&verificationCode=xyz",
                "/mono/report?email=qualcuno@cliente.example&from=2024-01-01&to=2024-12-31",
                "/mono/download-report?email=qualcuno@cliente.example&from=2024-01-01&to=2024-12-31&onlyDelivered=true",
                "/mono/request-success.html",
                "/mono/mono/email-success.html",
                "/mono/mono/report.html"
            })
    void oldEmailLinksGetTheItalianPageOnBothHosts(String url) throws Exception {
        for (String host : List.of(APEX, APP)) {
            String html = mvc.perform(on(host, url).accept("text/html"))
                    .andExpect(status().isGone())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(html)
                    .contains("<h1>Link non più valido</h1>")
                    .contains("href=\"/area-clienti/\"")
                    .contains("Vai all&#39;area clienti")
                    .doesNotContain("qualcuno@")
                    .doesNotContain("inesistente@")
                    .doesNotContain("confermat")
                    .doesNotContain("%PDF")
                    .doesNotContain(" style=\"")
                    .doesNotContain("<script>");
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer where email_verified_at is null and email like 'qualcuno%'",
                        Integer.class))
                .isZero();
    }

    @Test
    void theLogoOfTheOldEmailsIsServedFromTheSiteAssets() throws Exception {
        for (String host : List.of(APEX, APP)) {
            MvcResult result = mvc.perform(on(host, MonoGoneController.LOGO_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(header().string("Cache-Control", "max-age=604800, public"))
                    .andReturn();
            byte[] png = result.getResponse().getContentAsByteArray();
            assertThat(png.length).isGreaterThan(1000);
            assertThat(png[1]).isEqualTo((byte) 'P');
            assertThat(png[2]).isEqualTo((byte) 'N');
            assertThat(png[3]).isEqualTo((byte) 'G');
        }
    }

    private static MockHttpServletRequestBuilder on(String host, String url) {
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        MockHttpServletRequestBuilder builder = get(path).with(request -> {
            request.setServerName(host);
            request.addHeader("Host", host);
            return request;
        });
        if (url.contains("?")) {
            for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                builder.queryParam(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return builder;
    }
}
