package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.MutableClock;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-01 (chapter 08 §3.9): {@code create-staff-user} creates the account without a password
 * and prints a one-time set-password link; the link's page sets the password once (policy
 * enforced: 12 characters, not common), the user can then log in, and the link is dead
 * afterwards and after 24 hours. No password is read from the arguments or printed.
 */
@Tag("ADM-01")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Configuration.class)
class CreateStaffUserCommandIT extends AbstractPostgresIT {

    private static final Pattern LINK =
            Pattern.compile("https://logisticasanchez\\.com/gestione/imposta-password\\?t=([A-Za-z0-9_-]+)");

    @Autowired
    private CreateStaffUserCommand command;

    @Autowired
    private StaffUserRepository users;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    @Test
    void theCommandPrintsAOneTimeLinkThatSetsThePasswordOnce() throws Exception {
        String username = "Admin-" + UUID.randomUUID().toString().substring(0, 8);
        Output output = run("create-staff-user", "--username=" + username, "--role=ADMIN", "--name=Prima Admin");

        assertThat(output.code()).isZero();
        assertThat(output.err()).isEmpty();
        Matcher link = LINK.matcher(output.out());
        assertThat(link.find()).as("the link is printed: %s", output.out()).isTrue();
        String token = link.group(1);
        assertThat(output.out()).doesNotContainIgnoringCase("password:").contains("valido 24 ore");
        StaffUser created = users.findByUsername(username.toLowerCase()).orElseThrow();
        assertThat(created.getRole()).isEqualTo(StaffRole.ADMIN);
        assertThat(created.getPasswordHash())
                .as("no password until the user sets it")
                .isNull();
        assertThat(created.getDisplayName()).isEqualTo("Prima Admin");

        mvc.perform(get("/gestione/imposta-password").param("t", token).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(Matchers.containsString(username.toLowerCase())))
                .andExpect(content().string(Matchers.containsString("name=\"passwordConfirm\"")));
        assertThat(users.findByUsername(username.toLowerCase()).orElseThrow().getPasswordHash())
                .as("the GET sets nothing")
                .isNull();

        mvc.perform(setPassword(token, "short", "short"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("almeno 12 caratteri")));
        mvc.perform(setPassword(token, "passwordpassword", "passwordpassword"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("troppo comune")));
        mvc.perform(setPassword(token, "pallet-espresso-brianza-01", "pallet-espresso-brianza-02"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("non coincidono")));
        mvc.perform(setPassword(token, "pallet-espresso-brianza-01", "pallet-espresso-brianza-01"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/?impostata=1"));
        assertThat(users.findByUsername(username.toLowerCase()).orElseThrow().getPasswordHash())
                .startsWith("{bcrypt}");

        mvc.perform(post("/gestione/login")
                        .param("username", username)
                        .param("password", "pallet-espresso-brianza-01")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));

        mvc.perform(get("/gestione/imposta-password").param("t", token).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        mvc.perform(setPassword(token, "another-long-password-xyz", "another-long-password-xyz"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        assertThat(users.findByUsername(username.toLowerCase()).orElseThrow().getPasswordHash())
                .as("the second POST changed nothing")
                .startsWith("{bcrypt}");
    }

    @Test
    void theLinkExpiresAfter24Hours() throws Exception {
        String username = "op-" + UUID.randomUUID().toString().substring(0, 8);
        Output output = run("create-staff-user", "--username=" + username, "--role=operator");
        assertThat(output.code()).isZero();
        Matcher link = LINK.matcher(output.out());
        assertThat(link.find()).isTrue();
        clock.advance(Duration.ofHours(24).plusMinutes(1));
        mvc.perform(get("/gestione/imposta-password").param("t", link.group(1)).accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        mvc.perform(setPassword(link.group(1), "pallet-espresso-brianza-01", "pallet-espresso-brianza-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Il link non è più valido")));
        assertThat(users.findByUsername(username).orElseThrow().getPasswordHash())
                .isNull();
    }

    @Test
    void badArgumentsAndTakenUsernamesFailWithoutCreatingAnything() {
        String username = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(run("create-staff-user", "--username=" + username).code()).isEqualTo(2);
        assertThat(users.findByUsername(username)).isEmpty();
        assertThat(run("create-staff-user", "--username=" + username, "--role=BOSS")
                        .code())
                .isEqualTo(2);
        assertThat(users.findByUsername(username)).isEmpty();
        assertThat(run("create-staff-user", "--username=" + username, "--role=OPERATOR")
                        .code())
                .isZero();
        Output duplicate = run("create-staff-user", "--username=" + username.toUpperCase(), "--role=ADMIN");
        assertThat(duplicate.code()).isEqualTo(1);
        assertThat(duplicate.err()).contains("già in uso");
        assertThat(users.findByUsername(username).orElseThrow().getRole()).isEqualTo(StaffRole.OPERATOR);
        command.run(new DefaultApplicationArguments("--username=" + username, "--role=ADMIN"));
        assertThat(command.getExitCode())
                .as("without the command word the runner does nothing")
                .isZero();
    }

    /** {@code --ask-password}: the password typed twice at the terminal, the policy applied, no link needed. */
    @Test
    void thePasswordCanBeTypedAtTheTerminal() throws Exception {
        String username = "tty-" + UUID.randomUUID().toString().substring(0, 8);
        Deque<String> typed = new ArrayDeque<>(List.of(
                "short", "short", // too short
                "pallet-espresso-brianza-01", "pallet-espresso-brianza-02", // mismatch
                "pallet-espresso-brianza-01", "pallet-espresso-brianza-01"));
        Output output =
                run(terminal(typed), "create-staff-user", "--username=" + username, "--role=ADMIN", "--ask-password");
        assertThat(output.code()).isZero();
        assertThat(output.err()).contains("almeno 12 caratteri").contains("non coincidono");
        assertThat(output.out()).contains("Password impostata").doesNotContain("imposta-password?t=");
        mvc.perform(post("/gestione/login")
                        .param("username", username)
                        .param("password", "pallet-espresso-brianza-01")
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/gestione/"));

        // three refusals: the account keeps no password and the link is printed instead
        String other = "tty2-" + UUID.randomUUID().toString().substring(0, 8);
        Deque<String> common = new ArrayDeque<>(
                List.of("passwordpassword", "passwordpassword", "passwordpassword", "passwordpassword", "x", "x"));
        Output refused =
                run(terminal(common), "create-staff-user", "--username=" + other, "--role=ADMIN", "--ask-password");
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("troppo comune");
        assertThat(LINK.matcher(refused.out()).find()).isTrue();
        assertThat(users.findByUsername(other).orElseThrow().getPasswordHash()).isNull();

        // no terminal: nothing is created
        String none = "tty3-" + UUID.randomUUID().toString().substring(0, 8);
        CreateStaffUserCommand.PasswordReader detached = new CreateStaffUserCommand.PasswordReader() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String read(String prompt) {
                throw new AssertionError("no terminal");
            }
        };
        assertThat(run(detached, "create-staff-user", "--username=" + none, "--role=ADMIN", "--ask-password")
                        .code())
                .isEqualTo(2);
        assertThat(users.findByUsername(none)).isEmpty();
    }

    private static CreateStaffUserCommand.PasswordReader terminal(Deque<String> typed) {
        return new CreateStaffUserCommand.PasswordReader() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String read(String prompt) {
                return typed.poll();
            }
        };
    }

    private Output run(CreateStaffUserCommand.PasswordReader reader, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = command.run(
                new DefaultApplicationArguments(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                reader);
        return new Output(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Output run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = command.run(
                new DefaultApplicationArguments(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Output(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder setPassword(
            String token, String password, String confirm) {
        return post("/gestione/imposta-password")
                .param("t", token)
                .param("password", password)
                .param("passwordConfirm", confirm)
                .with(csrf())
                .accept("text/html");
    }

    private record Output(int code, String out, String err) {}
}
