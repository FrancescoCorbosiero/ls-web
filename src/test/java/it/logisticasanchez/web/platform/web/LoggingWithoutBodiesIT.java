package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * NFR-04 (chapter 10 §5, ADR 0019; SEC-07 not reproduced): on a login attempt and on a POST
 * with a body, no log line carries the request or response body, the password, the e-mail
 * address, the one-time token or the query string; the access line carries method, path,
 * status, duration and the request id only.
 */
@Tag("NFR-04")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
class LoggingWithoutBodiesIT extends AbstractPostgresIT {

    private static final String EMAIL = "cliente.riservato@ls.example";
    private static final String PASSWORD = "Password-Riservata-123!";
    private static final String TOKEN = "tokenSegretoAbc123XYZ789";
    private static final String BODY_MARKER = "corpo-riservato-della-richiesta";

    @Autowired
    private MockMvc mvc;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void capture() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void release() {
        root.detachAppender(captured);
        captured.stop();
    }

    @Test
    void aLoginAttemptAndAPostLeaveNoBodyTokenOrAddressInTheLogs() throws Exception {
        mvc.perform(post("/gestione/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", EMAIL)
                .param("password", PASSWORD));
        MvcResult posted = mvc.perform(post("/api/form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"messaggio\":\"" + BODY_MARKER + "\"}"))
                .andReturn();
        mvc.perform(get("/area-clienti/accesso").queryParam("t", TOKEN).accept(MediaType.TEXT_HTML));
        mvc.perform(post("/fixture/errors/json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + BODY_MARKER + "\",\"email\":\"" + EMAIL + "\"}"));

        List<String> lines =
                captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines))
                .doesNotContain(PASSWORD)
                .doesNotContain(EMAIL)
                .doesNotContain(TOKEN)
                .doesNotContain(BODY_MARKER)
                .doesNotContain("t=" + TOKEN)
                .doesNotContain(posted.getResponse().getContentAsString().substring(0, 20));
        assertThat(lines)
                .as("one access line per request with method, path and status")
                .anyMatch(line -> line.startsWith("access method=POST path=/gestione/login status="))
                .anyMatch(line -> line.startsWith("access method=GET path=/area-clienti/accesso status="))
                .noneMatch(line -> line.contains("?t="));
        assertThat(captured.list.stream().map(event -> event.getMDCPropertyMap().get(RequestId.MDC_KEY)))
                .as("the access lines carry the request id in the MDC")
                .anyMatch(id -> id != null && id.length() >= 8);
    }
}
