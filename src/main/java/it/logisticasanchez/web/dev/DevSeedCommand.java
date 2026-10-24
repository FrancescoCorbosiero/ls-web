package it.logisticasanchez.web.dev;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code dev-seed} (local and test profiles only; ADM-01 forbids seeded staff elsewhere): two
 * staff accounts, {@code admin.seed} (ADMIN) and {@code operatore.seed} (OPERATOR), with random
 * passwords printed once, and two synthetic customers with their login identity — one ACTIVE and
 * one PENDING_APPROVAL — on {@code .example} addresses. Re-running it sets new passwords on the
 * seed accounts and leaves existing customers alone. Run as {@code java -jar app.jar dev-seed
 * --spring.profiles.active=local}; the bean does not exist outside local and test.
 */
@Component
@Profile({"local", "test"})
public class DevSeedCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String NAME = "dev-seed";
    public static final String ADMIN_USERNAME = "admin.seed";
    public static final String OPERATOR_USERNAME = "operatore.seed";
    public static final String ACTIVE_CUSTOMER_EMAIL = "cliente.attivo@cliente.example";
    public static final String PENDING_CUSTOMER_EMAIL = "cliente.in-attesa@cliente.example";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StaffUserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile int exitCode;

    public DevSeedCommand(StaffUserRepository users, PasswordEncoder encoder, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static boolean isRequested(String... args) {
        return List.of(args).contains(NAME);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().contains(NAME)) {
            exitCode = run(System.out);
        }
    }

    /** Seeds and prints the passwords once; returns the exit code. One transaction: all or nothing. */
    @Transactional
    public int run(PrintStream out) {
        out.println("Dati di prova (solo profili local e test). Le password sono mostrate una sola volta:");
        out.println("  " + ADMIN_USERNAME + " (ADMIN)        password: "
                + staff(ADMIN_USERNAME, "Admin Seed", StaffRole.ADMIN));
        out.println("  " + OPERATOR_USERNAME + " (OPERATOR) password: "
                + staff(OPERATOR_USERNAME, "Operatore Seed", StaffRole.OPERATOR));
        out.println("  cliente ACTIVE            e-mail: " + ACTIVE_CUSTOMER_EMAIL
                + customer(ACTIVE_CUSTOMER_EMAIL, "Ditta Attiva S.r.l.", "ACTIVE"));
        out.println("  cliente PENDING_APPROVAL  e-mail: " + PENDING_CUSTOMER_EMAIL
                + customer(PENDING_CUSTOMER_EMAIL, "Ditta In Attesa S.r.l.", "PENDING_APPROVAL"));
        return 0;
    }

    private String staff(String username, String displayName, StaffRole role) {
        String password = randomPassword();
        StaffUser user = users.findByUsername(username).orElseGet(() -> new StaffUser(username, displayName, role));
        user.setEmail(username + "@staff.example");
        user.setPasswordHash(encoder.encode(password));
        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setActive(true);
        user.setUpdatedAt(clock.instant());
        users.save(user);
        return password;
    }

    /** The customer entity belongs to the Phase 2 customers module: the dev seed writes the rows directly. */
    private String customer(String email, String companyName, String status) {
        Integer existing = jdbc.queryForObject("select count(*) from customer where email = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            return " (già presente)";
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        Long customerId = jdbc.queryForObject(
                "insert into customer (email, company_name, address_line, city, province, postal_code, phone,"
                        + " status, email_verified_at, approved_at, source)"
                        + " values (?, ?, 'Via di Prova 1', 'Seregno', 'MB', '20831', '+39 333 000 0000', ?, ?, ?,"
                        + " 'CUSTOMER_AREA') returning id",
                Long.class,
                email,
                companyName,
                status,
                now,
                "ACTIVE".equals(status) ? now : null);
        jdbc.update("insert into customer_user (customer_id, email) values (?, ?)", customerId, email);
        return "";
    }

    /** 20 characters of base64url: 120 random bits, above the 12-character policy. */
    static String randomPassword() {
        byte[] bytes = new byte[15];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
