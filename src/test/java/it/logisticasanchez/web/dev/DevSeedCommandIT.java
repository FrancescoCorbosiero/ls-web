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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-01 / CUS-01: {@code dev-seed} exists only under the local and test profiles, creates the
 * two seed staff accounts with random passwords printed once (the ADMIN can log in with the
 * printed password), synthetic customers with their login identity in every state on
 * {@code .example} addresses and one order per order/tracking state for the ACTIVE one; a second
 * run rotates the passwords and leaves customers and orders alone.
 */
@Tag("ADM-01")
@Tag("CUS-01")
@SpringBootTest
@AutoConfigureMockMvc
class DevSeedCommandIT extends AbstractPostgresIT {

    private static final Pattern PASSWORD =
            Pattern.compile("(admin\\.seed|operatore\\.seed) \\((ADMIN|OPERATOR)\\)\\s+password: (\\S+)");

    @Autowired
    private DevSeedCommand command;

    @Autowired
    private StaffUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

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

        assertThat(customerStatus(DevSeedCommand.ACTIVE_CUSTOMER_EMAIL)).isEqualTo("ACTIVE");
        assertThat(customerStatus(DevSeedCommand.PENDING_CUSTOMER_EMAIL)).isEqualTo("PENDING_APPROVAL");
        assertThat(customerStatus(DevSeedCommand.UNVERIFIED_CUSTOMER_EMAIL)).isEqualTo("PENDING_VERIFICATION");
        assertThat(customerStatus(DevSeedCommand.DECLINED_CUSTOMER_EMAIL)).isEqualTo("DECLINED");
        assertThat(jdbc.queryForList(
                        "select o.status || '/' || o.tracking_state_id from shipment_order o join customer c on c.id = o.customer_id"
                                + " where c.email = ? order by o.id",
                        String.class,
                        DevSeedCommand.ACTIVE_CUSTOMER_EMAIL))
                .containsExactly("PENDING/0", "APPROVED/1", "APPROVED/5", "DECLINED/0");
        // first run on a fresh database: the codes are printed; on a reused one: «già presenti»
        assertThat(out)
                .containsPattern(
                        "ordini del cliente ACTIVE: (già presenti|TEMP-[A-Z2-9]{8} \\(PENDING\\), TEST-0002 \\(APPROVED, PRESA\\).*)");
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
