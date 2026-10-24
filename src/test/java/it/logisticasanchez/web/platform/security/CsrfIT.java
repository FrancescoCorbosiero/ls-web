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
        // No handler exists yet for /api/form and /mono/** (Phase 2): the point is that the CSRF
        // filter lets the request through (404 from the router, never 403).
        mvc.perform(post("/api/form").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/mono/api/order/trace/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        // A public POST route that is not on the exemptable list still needs the token.
        mvc.perform(post("/fixture/form").param("email", "a@ls.example")).andExpect(status().isForbidden());
        mvc.perform(post("/fixture/form").param("email", "a@ls.example").with(csrf()))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new SecurityRoutes().csrfExempt(org.springframework.http.HttpMethod.POST, "/fixture/form"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
