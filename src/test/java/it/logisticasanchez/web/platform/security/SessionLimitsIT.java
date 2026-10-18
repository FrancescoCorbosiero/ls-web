package it.logisticasanchez.web.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.Sessions;
import it.logisticasanchez.web.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR-02 (chapter 10 §2 sessions): customers 60 min idle / 12 h absolute, staff 30 min / 10 h.
 * The idle limit is the session's max-inactive interval; past the absolute limit the session
 * is invalidated and the request is sent to the sign-in page.
 */
@Tag("NFR-02")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class SessionLimitsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void idleTimeoutsFollowTheSessionClass() throws Exception {
        MockHttpSession customer = Sessions.customer(Instant.now());
        mvc.perform(get("/area-clienti/fixture").session(customer)).andExpect(status().isOk());
        assertThat(customer.getMaxInactiveInterval()).isEqualTo((int) Duration.ofMinutes(60).toSeconds());

        MockHttpSession staff = Sessions.staff("OPERATOR", Instant.now());
        mvc.perform(get("/gestione/fixture").session(staff)).andExpect(status().isOk());
        assertThat(staff.getMaxInactiveInterval()).isEqualTo((int) Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void theFirstAuthenticatedRequestStampsTheSession() throws Exception {
        MockHttpSession session = Sessions.customer(null);
        mvc.perform(get("/area-clienti/fixture").session(session)).andExpect(status().isOk());
        assertThat(session.getAttribute(SessionLimitsFilter.AUTHENTICATED_AT)).isInstanceOf(Instant.class);
    }

    @Test
    void pastTheAbsoluteLimitTheSessionIsInvalidatedAndTheUserSentToSignIn() throws Exception {
        MockHttpSession customer = Sessions.customer(Instant.now().minus(Duration.ofHours(12).plusMinutes(1)));
        mvc.perform(get("/area-clienti/fixture").session(customer).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/area-clienti/"));
        assertThat(customer.isInvalid()).isTrue();

        MockHttpSession staff = Sessions.staff("ADMIN", Instant.now().minus(Duration.ofHours(10).plusMinutes(1)));
        mvc.perform(get("/gestione/fixture/admin").session(staff).accept("text/html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));
        assertThat(staff.isInvalid()).isTrue();

        MockHttpSession fresh = Sessions.staff("ADMIN", Instant.now().minus(Duration.ofHours(9)));
        mvc.perform(get("/gestione/fixture/admin").session(fresh)).andExpect(status().isOk());
        assertThat(fresh.isInvalid()).isFalse();
    }
}
