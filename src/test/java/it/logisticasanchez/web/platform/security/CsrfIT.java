package it.logisticasanchez.web.platform.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * NFR-02 (chapter 10 §4): CSRF on every customer-area and back-office POST — a missing or stale
 * token answers 403 with the CSRF reason, a valid one goes through; {@code POST /api/form} and
 * {@code /mono/**} are exempt (session-free); every other exemption is refused.
 */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class CsrfIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void privateAreaPostsNeedTheToken() throws Exception {
        mvc.perform(post("/area-clienti/fixture")
                        .with(user("c").roles("CUSTOMER"))
                        .header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_rejected"))
                .andExpect(jsonPath("$.reason").value("La sessione non è più valida. Ricarica la pagina e riprova."));
        mvc.perform(post("/gestione/fixture").with(user("op").roles("OPERATOR")).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
        mvc.perform(post("/gestione/fixture").with(user("op").roles("OPERATOR")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/area-clienti/fixture")
                        .with(user("c").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/gestione/fixture").with(user("op").roles("OPERATOR")).with(csrf().asHeader()))
                .andExpect(status().isOk());
    }

    @Test
    void theSessionFreePublicEndpointsAreExemptAndNothingElseIs() throws Exception {
        // The CSRF filter lets the request through to the handlers: /api/form answers its own 400
        // «unknown form» (the Origin header satisfies its Origin/Referer check, ADR 0014) and the
        // tracking lookup its 400 for an empty code — never the 403 of a missing token.
        mvc.perform(post("/api/form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown_form"));
        mvc.perform(post("/mono/api/order/trace/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
        // A public POST route that is not on the exemptable list still needs the token.
        mvc.perform(post("/fixture/form").param("email", "a@ls.example")).andExpect(status().isForbidden());
        mvc.perform(post("/fixture/form").param("email", "a@ls.example").with(csrf()))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new SecurityRoutes().csrfExempt(org.springframework.http.HttpMethod.POST, "/fixture/form"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
