package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.platform.web.error.ErrorCode;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * NFR-02, ADR 0002: one JSON error schema ({@code status, code, reason, requestId, timestamp},
 * optional {@code detail}) for API and HTMX requests and an HTML page for browsers, explicit
 * 400/404/405/409/415/422 for client errors (the legacy answered 500, SEC-09), 500 with the
 * generic reason and no internals for the unexpected; never a stack trace or a framework page.
 */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class ErrorModelIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void malformedJsonIs400WithTheSchema() throws Exception {
        schema(
                mvc.perform(post("/fixture/errors/json")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not json"))
                        .andExpect(status().isBadRequest()),
                ErrorCode.BAD_REQUEST);
    }

    @Test
    void validationFailureIs422() throws Exception {
        schema(
                mvc.perform(post("/fixture/errors/json")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                        .andExpect(status().isUnprocessableContent()),
                ErrorCode.VALIDATION_FAILED);
        schema(
                mvc.perform(get("/fixture/errors/param").param("n", "abc").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isBadRequest()),
                ErrorCode.BAD_REQUEST);
    }

    @Test
    void wrongMethodAndMediaTypeAre405And415() throws Exception {
        schema(
                mvc.perform(post("/fixture/errors/boom").with(csrf()).accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isMethodNotAllowed())
                        .andExpect(header().string("Allow", "GET")),
                ErrorCode.METHOD_NOT_ALLOWED);
        schema(
                mvc.perform(post("/fixture/errors/json")
                                .with(csrf())
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("x")
                                .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isUnsupportedMediaType()),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void domainClientErrorsKeepTheirStatusAndTheLegacyItalianReasons() throws Exception {
        schema(
                mvc.perform(get("/fixture/errors/not-found").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isNotFound()),
                ErrorCode.NOT_FOUND);
        schema(
                mvc.perform(get("/fixture/errors/conflict").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.reason")
                                .value("E' già stata effettuata una registrazione con questo codice."
                                        + " Si prega di riprovare con un nuovo codice.")),
                ErrorCode.CODE_CONFLICT);
    }

    @Test
    void twoChangesOfTheSameRecordAtOnceAre409WithAClearReason() throws Exception {
        // ADR 0028: the services refuse a stale page themselves; the version check at commit
        // catches the race of two saves at the same moment
        schema(
                mvc.perform(post("/fixture/errors/stale").with(csrf()).accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.reason")
                                .value("I dati sono stati modificati nel frattempo da un'altra persona."
                                        + " Ricarica la pagina e riprova.")),
                ErrorCode.STALE_DATA);
    }

    @Test
    void unexpectedExceptionsAre500WithTheGenericReasonAndNoInternals() throws Exception {
        MvcResult json = schema(
                        mvc.perform(get("/fixture/errors/boom").accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isInternalServerError()),
                        ErrorCode.INTERNAL_ERROR)
                .andReturn();
        assertThat(json.getResponse().getContentAsString())
                .doesNotContain("secret internal detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at it.logisticasanchez");

        MvcResult html = mvc.perform(get("/fixture/errors/boom").accept(MediaType.TEXT_HTML))
                .andExpect(status().isInternalServerError())
                .andReturn();
        assertThat(html.getResponse().getContentType()).startsWith("text/html");
        assertThat(html.getResponse().getContentAsString())
                .contains("Qualcosa è andato storto")
                .doesNotContain("secret internal detail")
                .doesNotContain("Whitelabel")
                .doesNotContain("Exception")
                .doesNotContain("style=\"")
                .doesNotContain("<script>")
                .doesNotContain("application/ld+json");
    }

    @Test
    void browsersGetTheHtmlPageAndHtmxGetsJson() throws Exception {
        MvcResult page = mvc.perform(get("/fixture/errors/not-found").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(page.getResponse().getContentType()).startsWith("text/html");
        assertThat(page.getResponse().getContentAsString())
                .contains("Pagina non trovata")
                .contains("Logistica Sanchez")
                .doesNotContain("Whitelabel");
        schema(
                mvc.perform(get("/fixture/errors/not-found")
                                .accept(MediaType.TEXT_HTML)
                                .header("HX-Request", "true"))
                        .andExpect(status().isNotFound()),
                ErrorCode.NOT_FOUND);
        // The error dispatch endpoint itself never shows a framework page either.
        mvc.perform(get("/error").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        schema(
                mvc.perform(get("/error").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotFound()),
                ErrorCode.NOT_FOUND);
    }

    private static ResultActions schema(ResultActions actions, ErrorCode code) throws Exception {
        return actions.andExpect(header().string("Content-Type", "application/json"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()))
                .andExpect(jsonPath("$.reason").value(code.reason()))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }
}
