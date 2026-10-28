package it.logisticasanchez.web.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADM-01 / CUS-01: {@code dev-seed} exists only under the local and test profiles, creates the
 * two seed staff accounts with random passwords printed once (the ADMIN can log in with the
 * printed password), synthetic customers with their login identity in every state on
 * {@code .example} addresses, one order per order/tracking state for the ACTIVE one and three
 * website requests (ENH-06); a second run rotates the passwords and leaves customers, orders and
 * requests alone.
 */
@Tag("ADM-01")
@Tag("CUS-01")
@SpringBootTest
@AutoConfigureMockMvc
class DevSeedCommandIT extends AbstractPostgresIT {

    private static final Pattern PASSWORD =
            Pattern.compile("(admin\\.seed|operatore\\.seed) \\((ADMIN|OPERATOR)\\)\\s+password: (\\S+)");
    private static final Pattern CUSTOMER_PASSWORD = Pattern.compile("cliente ACTIVE .*  password: (\\S+)");

    @Autowired
    private DevSeedCommand command;

    @Autowired
    private StaffUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder encoder;

    @Test
    void theCommandIsLimitedToTheLocalAndTestProfiles() {
        assertThat(DevSeedCommand.class.getAnnotation(Profile.class).value())
                .containsExactlyInAnyOrder("local", "test");
        assertThat(DevSeedCommand.isRequested("dev-seed", "--spring.profiles.active=local"))
                .isTrue();
        assertThat(DevSeedCommand.isRequested("--spring.profiles.active=local")).isFalse();
    }

    @Test
    void theSeedCreatesStaffWithPrintedPasswordsAndTwoCustomers() throws Exception {
        String out = run();
        Matcher matcher = PASSWORD.matcher(out);
        assertThat(matcher.find()).as("admin password printed: %s", out).isTrue();
        String adminPassword = matcher.group(3);
        assertThat(matcher.find()).as("operator password printed: %s", out).isTrue();
        assertThat(matcher.group(1)).isEqualTo("operatore.seed");
        String operatorPassword = matcher.group(3);
        assertThat(adminPassword).hasSizeGreaterThanOrEqualTo(12).isNotEqualTo(operatorPassword);

        StaffUser admin = users.findByUsername(DevSeedCommand.ADMIN_USERNAME).orElseThrow();
        StaffUser operator =
                users.findByUsername(DevSeedCommand.OPERATOR_USERNAME).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(StaffRole.ADMIN);
        assertThat(operator.getRole()).isEqualTo(StaffRole.OPERATOR);
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}$2").doesNotContain(adminPassword);
        assertThat(admin.getEmail()).endsWith(".example");

        mvc.perform(post("/gestione/login")
                        .param("username", DevSeedCommand.ADMIN_USERNAME)
                        .param("password", adminPassword)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/gestione/"));

        // the ACTIVE seed customer signs in with its printed password (ADR 0032)
        Matcher customerPassword = CUSTOMER_PASSWORD.matcher(out);
        assertThat(customerPassword.find())
                .as("customer password printed: %s", out)
                .isTrue();
        mvc.perform(post("/area-clienti/password")
                        .param("email", DevSeedCommand.ACTIVE_CUSTOMER_EMAIL)
                        .param("password", customerPassword.group(1))
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/area-clienti/ordini/"));

        assertThat(customerStatus(DevSeedCommand.ACTIVE_CUSTOMER_EMAIL)).isEqualTo("ACTIVE");
        assertThat(customerStatus(DevSeedCommand.PENDING_CUSTOMER_EMAIL)).isEqualTo("PENDING_APPROVAL");
        assertThat(customerStatus(DevSeedCommand.UNVERIFIED_CUSTOMER_EMAIL)).isEqualTo("PENDING_VERIFICATION");
        assertThat(customerStatus(DevSeedCommand.DECLINED_CUSTOMER_EMAIL)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForList(
                        "select o.status || '/' || o.tracking_state_id from shipment_order o join customer c on c.id = o.customer_id"
                                + " where c.email = ? order by o.id",
                        String.class,
                        DevSeedCommand.ACTIVE_CUSTOMER_EMAIL))
                .containsExactly("PENDING/0", "APPROVED/1", "APPROVED/5", "DECLINED/0", "CANCELLED/0");
        // first run on a fresh database: the codes are printed; on a reused one: «già presenti», or
        // the orders of the states added since that database was seeded
        assertThat(out)
                .containsPattern(
                        "ordini del cliente ACTIVE: (già presenti|TEMP-[A-Z2-9]{8} \\(PENDING\\), TEST-0002 \\(APPROVED, PRESA\\).*"
                                + "|TEMP-[A-Z2-9]{8} \\(CANCELLED\\))");
        // ENH-06: three website requests through the form's validation, one already contacted
        assertThat(jdbc.queryForList(
                        "select form || '/' || status from lead where email like 'richiesta.%@cliente.example' order by id",
                        String.class))
                .containsExactly("PREVENTIVO/NEW", "CONTATTI/NEW", "PREVENTIVO/CONTACTED");
        assertThat(out).containsPattern("richieste dal sito: (già presenti|3 \\(2 nuove, 1 contattata\\))");
        assertThat(customerUsers(DevSeedCommand.ACTIVE_CUSTOMER_EMAIL)).isEqualTo(1);
        assertThat(customerUsers(DevSeedCommand.PENDING_CUSTOMER_EMAIL)).isEqualTo(1);
        assertThat(DevSeedCommand.ACTIVE_CUSTOMER_EMAIL).endsWith(".example");
        assertThat(DevSeedCommand.PENDING_CUSTOMER_EMAIL).endsWith(".example");

        // a second run: new passwords, the old one no longer works, customers untouched
        String again = run();
        Matcher second = PASSWORD.matcher(again);
        assertThat(second.find()).isTrue();
        assertThat(second.group(3)).isNotEqualTo(adminPassword);
        assertThat(again).contains("già presente");
        mvc.perform(post("/gestione/login")
                        .param("username", DevSeedCommand.ADMIN_USERNAME)
                        .param("password", adminPassword)
                        .with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/gestione/?errore=1"));
        assertThat(customerUsers(DevSeedCommand.ACTIVE_CUSTOMER_EMAIL)).isEqualTo(1);
        assertThat(again).contains("ordini del cliente ACTIVE: già presenti");
        assertThat(again).contains("richieste dal sito: già presenti");
        assertThat(jdbc.queryForObject(
                        "select count(*) from customer where email in (?, ?)",
                        Integer.class,
                        DevSeedCommand.ACTIVE_CUSTOMER_EMAIL,
                        DevSeedCommand.PENDING_CUSTOMER_EMAIL))
                .isEqualTo(2);

        command.run(new DefaultApplicationArguments("--username=x"));
        assertThat(command.getExitCode())
                .as("without the command word the runner does nothing")
                .isZero();
    }

    /**
     * {@code --staff-only} resets the two staff accounts and adds nothing synthetic;
     * {@code --customers-password} gives every customer login one printed password (the local copy
     * of the legacy data, ADR 0048). Rolled back: the test database is shared.
     */
    @Test
    @Transactional
    void theNarrowedRunsTouchOnlyWhatTheyName() {
        run();
        Integer customersBefore = jdbc.queryForObject("select count(*) from customer", Integer.class);
        Integer ordersBefore = jdbc.queryForObject("select count(*) from shipment_order", Integer.class);
        String adminHashBefore = users.findByUsername(DevSeedCommand.ADMIN_USERNAME)
                .orElseThrow()
                .getPasswordHash();

        ByteArrayOutputStream staffOnly = new ByteArrayOutputStream();
        assertThat(command.runNarrowed(new PrintStream(staffOnly, true, StandardCharsets.UTF_8), true, null, false))
                .isZero();
        Matcher staff = PASSWORD.matcher(staffOnly.toString(StandardCharsets.UTF_8));
        assertThat(staff.find()).isTrue();
        assertThat(encoder.matches(
                        staff.group(3),
                        users.findByUsername(DevSeedCommand.ADMIN_USERNAME)
                                .orElseThrow()
                                .getPasswordHash()))
                .isTrue();
        assertThat(staffOnly.toString(StandardCharsets.UTF_8))
                .doesNotContain("cliente")
                .doesNotContain("ordini");

        String adminHash = users.findByUsername(DevSeedCommand.ADMIN_USERNAME)
                .orElseThrow()
                .getPasswordHash();
        ByteArrayOutputStream customers = new ByteArrayOutputStream();
        assertThat(command.runNarrowed(new PrintStream(customers, true, StandardCharsets.UTF_8), false, null, true))
                .isZero();
        Matcher shared =
                Pattern.compile("password di tutti: (\\S+)").matcher(customers.toString(StandardCharsets.UTF_8));
        assertThat(shared.find()).as("shared password printed: %s", customers).isTrue();
        assertThat(jdbc.queryForList("select password_hash from customer_user", String.class))
                .isNotEmpty()
                .allSatisfy(hash ->
                        assertThat(encoder.matches(shared.group(1), hash)).isTrue());
        assertThat(users.findByUsername(DevSeedCommand.ADMIN_USERNAME)
                        .orElseThrow()
                        .getPasswordHash())
                .isEqualTo(adminHash)
                .isNotEqualTo(adminHashBefore);
        assertThat(jdbc.queryForObject("select count(*) from customer", Integer.class))
                .isEqualTo(customersBefore);
        assertThat(jdbc.queryForObject("select count(*) from shipment_order", Integer.class))
                .isEqualTo(ordersBefore);
    }

    /**
     * {@code --legacy-sql}: the import file runs as one script on its own connection, psql
     * meta-commands skipped, its notices printed; the file is deleted whether it worked or not (it
     * holds real data). Synthetic scripts that write nothing.
     */
    @Test
    void theLegacyFileRunsOnceAndIsDeleted(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok.sql");
        Files.writeString(ok, """
                \\set ON_ERROR_STOP on
                BEGIN;
                CREATE TEMP TABLE imp_probe (n integer) ON COMMIT DROP;
                INSERT INTO imp_probe VALUES (1), (2);
                DO $$
                BEGIN
                    RAISE NOTICE 'legacy import: % righe di prova', (SELECT count(*) FROM imp_probe);
                END $$;
                COMMIT;
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(command.runNarrowed(new PrintStream(out, true, StandardCharsets.UTF_8), false, ok, false))
                .isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("legacy import: 2 righe di prova")
                .contains("dati legacy caricati");
        assertThat(ok).doesNotExist();

        Path failing = dir.resolve("failing.sql");
        Files.writeString(failing, "DO $$ BEGIN RAISE EXCEPTION 'legacy import: already loaded'; END $$;\n");
        ByteArrayOutputStream refused = new ByteArrayOutputStream();
        assertThat(command.runNarrowed(new PrintStream(refused, true, StandardCharsets.UTF_8), false, failing, true))
                .isEqualTo(1);
        assertThat(refused.toString(StandardCharsets.UTF_8))
                .contains("importazione non riuscita")
                .contains("already loaded")
                .doesNotContain("password di tutti");
        assertThat(failing).doesNotExist();

        ByteArrayOutputStream missing = new ByteArrayOutputStream();
        assertThat(command.runNarrowed(
                        new PrintStream(missing, true, StandardCharsets.UTF_8), false, dir.resolve("none.sql"), false))
                .isEqualTo(1);
        assertThat(missing.toString(StandardCharsets.UTF_8)).contains("non trovato");
    }

    private String run() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = command.run(new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(code).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    private String customerStatus(String email) {
        return jdbc.queryForObject("select status from customer where email = ?", String.class, email);
    }

    private int customerUsers(String email) {
        Integer count = jdbc.queryForObject(
                "select count(*) from customer_user cu join customer c on c.id = cu.customer_id where c.email = ?",
                Integer.class,
                email);
        return count == null ? 0 : count;
    }
}
