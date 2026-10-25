package it.logisticasanchez.web.dev;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.tracking.TrackingState;
import java.io.PrintStream;
import java.math.BigDecimal;
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
 * passwords printed once; synthetic customers with their login identity in every state (ACTIVE,
 * PENDING_APPROVAL, PENDING_VERIFICATION, DECLINED) on {@code .example} addresses; and, for the
 * ACTIVE customer, one order per order and tracking state (pending, approved in PRESA, approved
 * and delivered, declined) through the real services, so their e-mails land in the local mail
 * directory. Re-running it sets new passwords on the seed accounts and leaves existing customers
 * and orders alone. Run as {@code java -jar app.jar dev-seed
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
    public static final String UNVERIFIED_CUSTOMER_EMAIL = "cliente.da-verificare@cliente.example";
    public static final String DECLINED_CUSTOMER_EMAIL = "cliente.rifiutato@cliente.example";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StaffUserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final CustomerService customers;
    private final OrderService orders;
    private final Clock clock;
    private volatile int exitCode;

    public DevSeedCommand(
            StaffUserRepository users,
            PasswordEncoder encoder,
            JdbcTemplate jdbc,
            CustomerService customers,
            OrderService orders,
            Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.customers = customers;
        this.orders = orders;
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
        out.println("  cliente PENDING_VERIFICATION e-mail: " + UNVERIFIED_CUSTOMER_EMAIL
                + customer(UNVERIFIED_CUSTOMER_EMAIL, "Ditta Da Verificare S.r.l.", "PENDING_VERIFICATION"));
        out.println("  cliente DECLINED          e-mail: " + DECLINED_CUSTOMER_EMAIL
                + customer(DECLINED_CUSTOMER_EMAIL, "Ditta Rifiutata S.r.l.", "DECLINED"));
        out.println("  ordini del cliente ACTIVE: "
                + orders(users.findByUsername(ADMIN_USERNAME).orElseThrow().getId()));
        return 0;
    }

    /** One order per state for the ACTIVE customer: pending, approved (PRESA), delivered (CONSEGNATO), declined. */
    private String orders(long staffId) {
        long customerId =
                jdbc.queryForObject("select id from customer where email = ?", Long.class, ACTIVE_CUSTOMER_EMAIL);
        long userId =
                jdbc.queryForObject("select id from customer_user where email = ?", Long.class, ACTIVE_CUSTOMER_EMAIL);
        Integer existing = jdbc.queryForObject(
                "select count(*) from shipment_order where customer_id = ?", Integer.class, customerId);
        if (existing != null && existing > 0) {
            return "già presenti";
        }
        OrderView pending =
                orders.submit(customerId, userId, draft("DDT-SEED-0001", "Destinatario Uno S.r.l.", "Milano", "20121"));
        OrderView approved =
                orders.submit(customerId, userId, draft("DDT-SEED-0002", "Destinatario Due S.r.l.", "Como", "22100"));
        orders.approve(approved.id(), staffId, "TEST-0002", "180,50");
        OrderView delivered =
                orders.submit(customerId, userId, draft("DDT-SEED-0003", "Destinatario Tre S.r.l.", "Lecco", "23900"));
        orders.approve(delivered.id(), staffId, "TEST-0003", "95,00");
        orders.setTrackingState(delivered.id(), staffId, TrackingState.CONSEGNATO);
        OrderView declined = orders.submit(
                customerId, userId, draft("DDT-SEED-0004", "Destinatario Quattro S.r.l.", "Bergamo", "24121"));
        orders.decline(declined.id(), staffId, "Merce non trasportabile");
        return pending.tempCode() + " (PENDING), TEST-0002 (APPROVED, PRESA), TEST-0003 (APPROVED, CONSEGNATO), "
                + declined.tempCode() + " (DECLINED)";
    }

    private static OrderDraft draft(String ddt, String recipient, String city, String cap) {
        OrderDraft draft = new OrderDraft();
        draft.addLine(LineKind.EPAL, 180, 120, 80, new BigDecimal("450"), 2);
        draft.addLine(LineKind.GENERIC, 100, 120, 80, new BigDecimal("300"), 1);
        draft.setDetails(ddt, "3", null, "Ordine di prova");
        draft.setRecipient(new OrderDraft.Recipient(
                recipient, "Via di Prova 2", city.substring(0, 2).toUpperCase(), city, cap, null, null));
        return draft;
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
                        + " status, email_verified_at, approved_at, declined_at, source)"
                        + " values (?, ?, 'Via di Prova 1', 'Seregno', 'MB', '20831', '+393330000000', ?, ?, ?, ?,"
                        + " 'CUSTOMER_AREA') returning id",
                Long.class,
                email,
                companyName,
                status,
                "PENDING_VERIFICATION".equals(status) ? null : now,
                "ACTIVE".equals(status) ? now : null,
                "DECLINED".equals(status) ? now : null);
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
