package it.logisticasanchez.web.dev;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.LineKind;
import it.logisticasanchez.web.orders.OrderDraft;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.site.forms.FormSubmission;
import it.logisticasanchez.web.site.forms.FormValidator;
import it.logisticasanchez.web.site.forms.LeadService;
import it.logisticasanchez.web.site.forms.LeadStatus;
import it.logisticasanchez.web.tracking.TrackingState;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code dev-seed} (local and test profiles only; ADM-01 forbids seeded staff elsewhere): two
 * staff accounts, {@code admin.seed} (ADMIN) and {@code operatore.seed} (OPERATOR), with random
 * passwords printed once; synthetic customers with their login identity in every state (ACTIVE,
 * PENDING_APPROVAL, PENDING_VERIFICATION, DECLINED) on {@code .example} addresses; and, for the
 * ACTIVE customer, one order per order and tracking state (pending, approved in PRESA, approved
 * and delivered, declined, cancelled) through the real services, so their e-mails land in the local mail
 * directory; three website requests (ENH-06: a quote and a message, new, and a quote already
 * contacted) through the form's own validation. Re-running it sets new passwords on the seed
 * accounts and leaves existing customers, orders and requests alone (it only adds a seed order
 * that is missing). Run as {@code java -jar app.jar dev-seed
 * --spring.profiles.active=local}; the bean does not exist outside local and test.
 *
 * <p>Three options narrow the run, for a local copy of the legacy data ({@code scripts/dev.sh
 * --legacy}, {@code docker compose run --rm legacy}; ADR 0048), in this order when combined:
 * {@code --staff-only} creates or resets only the two staff accounts, with no synthetic customer,
 * order or request; {@code --legacy-sql=<file>} runs the import file written by
 * {@code tools/legacy-import/generate_import_sql.py} (it holds its own transaction) and deletes it,
 * since it holds real data; {@code --customers-password} sets one new random password, printed
 * once, on every customer login, so each imported customer can be opened in the customer area with
 * its e-mail. Production loads that file with psql, never through this command.
 */
@Component
@Profile({"local", "test"})
public class DevSeedCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String NAME = "dev-seed";
    public static final String STAFF_ONLY = "staff-only";
    public static final String CUSTOMERS_PASSWORD = "customers-password";
    public static final String LEGACY_SQL = "legacy-sql";
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
    private final FormValidator forms;
    private final LeadService leads;
    private final Clock clock;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private volatile int exitCode;

    public DevSeedCommand(
            StaffUserRepository users,
            PasswordEncoder encoder,
            JdbcTemplate jdbc,
            CustomerService customers,
            OrderService orders,
            FormValidator forms,
            LeadService leads,
            Clock clock,
            DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.customers = customers;
        this.orders = orders;
        this.forms = forms;
        this.leads = leads;
        this.clock = clock;
        this.dataSource = dataSource;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public static boolean isRequested(String... args) {
        return List.of(args).contains(NAME);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(NAME)) {
            return;
        }
        List<String> legacySql = args.getOptionValues(LEGACY_SQL);
        if (args.containsOption(STAFF_ONLY) || args.containsOption(CUSTOMERS_PASSWORD) || legacySql != null) {
            exitCode = runNarrowed(
                    System.out,
                    args.containsOption(STAFF_ONLY),
                    legacySql == null || legacySql.isEmpty() ? null : Path.of(legacySql.get(0)),
                    args.containsOption(CUSTOMERS_PASSWORD));
        } else {
            exitCode = transactions.execute(status -> run(System.out));
        }
    }

    /**
     * {@code --staff-only}, {@code --legacy-sql} and {@code --customers-password}, in this order:
     * nothing synthetic is added. The staff is committed before the import file runs on its own
     * connection (its internal notes need an ADMIN). Returns 1 when the file is missing or fails.
     */
    public int runNarrowed(PrintStream out, boolean staff, Path legacySql, boolean customersPassword) {
        out.println("Solo profili local e test. Le password sono mostrate una sola volta:");
        if (staff) {
            transactions.executeWithoutResult(status -> {
                out.println("  " + ADMIN_USERNAME + " (ADMIN)        password: "
                        + staff(ADMIN_USERNAME, "Admin Seed", StaffRole.ADMIN));
                out.println("  " + OPERATOR_USERNAME + " (OPERATOR) password: "
                        + staff(OPERATOR_USERNAME, "Operatore Seed", StaffRole.OPERATOR));
            });
        }
        if (legacySql != null && !loadLegacySql(out, legacySql)) {
            return 1;
        }
        if (customersPassword) {
            transactions.executeWithoutResult(status -> {
                String password = randomPassword();
                int count = jdbc.update(
                        "update customer_user set password_hash = ?, password_changed_at = ?",
                        encoder.encode(password),
                        Timestamp.from(Instant.now(clock)));
                out.println("  clienti (" + count + " accessi)   password di tutti: " + password);
            });
        }
        return 0;
    }

    /**
     * Runs the generated import file as one script on its own connection (the file has its own
     * BEGIN/COMMIT; psql meta-commands such as {@code \set} are skipped), prints its notices and
     * deletes the file whatever the outcome: it holds real personal data and is regenerated from
     * the dump.
     */
    private boolean loadLegacySql(PrintStream out, Path file) {
        if (!Files.isRegularFile(file)) {
            out.println("  file di importazione non trovato: " + file);
            return false;
        }
        try {
            String script = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.startsWith("\\"))
                    .collect(Collectors.joining("\n"));
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                connection.setAutoCommit(true);
                statement.execute(script);
                for (SQLWarning w = statement.getWarnings(); w != null; w = w.getNextWarning()) {
                    out.println("  " + w.getMessage());
                }
            }
            out.println("  dati legacy caricati");
            return true;
        } catch (IOException | SQLException e) {
            out.println("  importazione non riuscita: " + e.getMessage());
            return false;
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                out.println("  non riesco a cancellare " + file + ": cancellalo a mano (dati reali)");
            }
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
                + customer(ACTIVE_CUSTOMER_EMAIL, "Ditta Attiva S.r.l.", "ACTIVE")
                + "  password: " + customerPassword(ACTIVE_CUSTOMER_EMAIL));
        out.println("  cliente PENDING_APPROVAL  e-mail: " + PENDING_CUSTOMER_EMAIL
                + customer(PENDING_CUSTOMER_EMAIL, "Ditta In Attesa S.r.l.", "PENDING_APPROVAL"));
        out.println("  cliente PENDING_VERIFICATION e-mail: " + UNVERIFIED_CUSTOMER_EMAIL
                + customer(UNVERIFIED_CUSTOMER_EMAIL, "Ditta Da Verificare S.r.l.", "PENDING_VERIFICATION"));
        out.println("  cliente DECLINED          e-mail: " + DECLINED_CUSTOMER_EMAIL
                + customer(DECLINED_CUSTOMER_EMAIL, "Ditta Rifiutata S.r.l.", "DECLINED"));
        long adminId = users.findByUsername(ADMIN_USERNAME).orElseThrow().getId();
        out.println("  ordini del cliente ACTIVE: " + orders(adminId));
        out.println("  richieste dal sito: " + leads(adminId));
        return 0;
    }

    /** ENH-06: two new requests (a quote, a message) and a quote already contacted, unless seeded before. */
    private String leads(long staffId) {
        Integer seeded = jdbc.queryForObject(
                "select count(*) from lead where email like 'richiesta.%@cliente.example'", Integer.class);
        if (seeded != null && seeded > 0) {
            return "già presenti";
        }
        String pickupDay = LocalDate.now(clock.withZone(ZoneId.of("Europe/Rome")))
                .plusDays(7)
                .toString();
        Map<String, String> quote = new LinkedHashMap<>();
        quote.put("servizio", "pallet");
        quote.put("data_ritiro", pickupDay);
        quote.put("ritiro_cap", "20831");
        quote.put("ritiro_localita", "Seregno");
        quote.put("consegna_cap", "10121");
        quote.put("consegna_localita", "Torino");
        quote.put("destinatario", "azienda");
        quote.put("tipo_pallet", "eur");
        quote.put("numero", "2");
        quote.put("peso", "600");
        quote.put("altezza", "150");
        quote.put("descrizione_merce", "Ricambi meccanici");
        quote.put("servizi_extra[]", "Sponda idraulica");
        quote.put("nome", "Mario Prova");
        quote.put("azienda", "Officina Prova S.r.l.");
        quote.put("email", "richiesta.preventivo@cliente.example");
        quote.put("telefono", "+39 333 000 0001");
        quote.put("contatto_preferito", "telefono");
        quote.put("note", "Ritiro preferibilmente al mattino.");
        quote.put("privacy", "accettata");
        lead(FormSubmission.Form.PREVENTIVO, quote);
        Map<String, String> contact = new LinkedHashMap<>();
        contact.put("nome", "Lucia Esempio");
        contact.put("email", "richiesta.contatti@cliente.example");
        contact.put("oggetto", "Informazioni sul magazzino");
        contact.put("messaggio", "Vorrei sapere se offrite lo stoccaggio di 20 pallet per due mesi.");
        contact.put("privacy", "accettata");
        lead(FormSubmission.Form.CONTATTI, contact);
        Map<String, String> contacted = new LinkedHashMap<>(quote);
        contacted.put("servizio", "groupage");
        contacted.remove("data_ritiro");
        contacted.put("ritiro_cap", "20900");
        contacted.put("ritiro_localita", "Monza");
        contacted.put("consegna_cap", "40121");
        contacted.put("consegna_localita", "Bologna");
        contacted.put("destinatario", "privato");
        contacted.put("tipo_pallet", "industriale");
        contacted.put("numero", "1");
        contacted.put("peso", "300");
        contacted.remove("servizi_extra[]");
        contacted.put("nome", "Paolo Test");
        contacted.put("azienda", "Bottega Test S.n.c.");
        contacted.put("email", "richiesta.contattata@cliente.example");
        contacted.put("telefono", "+39 333 000 0002");
        contacted.put("contatto_preferito", "email");
        contacted.remove("note");
        leads.changeStatus(lead(FormSubmission.Form.PREVENTIVO, contacted), staffId, LeadStatus.CONTACTED);
        return "3 (2 nuove, 1 contattata)";
    }

    private long lead(FormSubmission.Form form, Map<String, String> fields) {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        fields.forEach((name, value) -> parameters.put(name, new String[] {value}));
        return leads.store(forms.validate(form, parameters), "127.0.0.1", "dev-seed");
    }

    /**
     * One order per state for the ACTIVE customer: pending, approved (PRESA), delivered
     * (CONSEGNATO), declined, cancelled. Each is created only when its DDT is missing, so a
     * database seeded by an earlier version gains the states added since.
     */
    private String orders(long staffId) {
        long customerId =
                jdbc.queryForObject("select id from customer where email = ?", Long.class, ACTIVE_CUSTOMER_EMAIL);
        long userId =
                jdbc.queryForObject("select id from customer_user where email = ?", Long.class, ACTIVE_CUSTOMER_EMAIL);
        List<String> created = new ArrayList<>();
        if (absent(customerId, "DDT-SEED-0001")) {
            OrderView pending = orders.submit(
                    customerId, userId, draft("DDT-SEED-0001", "Destinatario Uno S.r.l.", "Milano", "20121"));
            created.add(pending.tempCode() + " (PENDING)");
        }
        if (absent(customerId, "DDT-SEED-0002")) {
            OrderView approved = orders.submit(
                    customerId, userId, draft("DDT-SEED-0002", "Destinatario Due S.r.l.", "Como", "22100"));
            orders.approve(approved.id(), staffId, "TEST-0002", "180,50");
            created.add("TEST-0002 (APPROVED, PRESA)");
        }
        if (absent(customerId, "DDT-SEED-0003")) {
            OrderView delivered = orders.submit(
                    customerId, userId, draft("DDT-SEED-0003", "Destinatario Tre S.r.l.", "Lecco", "23900"));
            orders.approve(delivered.id(), staffId, "TEST-0003", "95,00");
            orders.setTrackingState(delivered.id(), staffId, TrackingState.CONSEGNATO);
            created.add("TEST-0003 (APPROVED, CONSEGNATO)");
        }
        if (absent(customerId, "DDT-SEED-0004")) {
            OrderView declined = orders.submit(
                    customerId, userId, draft("DDT-SEED-0004", "Destinatario Quattro S.r.l.", "Bergamo", "24121"));
            orders.decline(declined.id(), staffId, "Merce non trasportabile");
            created.add(declined.tempCode() + " (DECLINED)");
        }
        if (absent(customerId, "DDT-SEED-0005")) {
            OrderView cancelled = orders.submit(
                    customerId, userId, draft("DDT-SEED-0005", "Destinatario Cinque S.r.l.", "Lodi", "26900"));
            orders.cancelByStaff(
                    cancelled.id(),
                    staffId,
                    false,
                    orders.find(cancelled.id()).orElseThrow().version(),
                    "Il cliente ha annullato per telefono");
            created.add(cancelled.tempCode() + " (CANCELLED)");
        }
        return created.isEmpty() ? "già presenti" : String.join(", ", created);
    }

    private boolean absent(long customerId, String transportDocumentCode) {
        Integer count = jdbc.queryForObject(
                "select count(*) from shipment_order where customer_id = ? and transport_document_code = ?",
                Integer.class,
                customerId,
                transportDocumentCode);
        return count == null || count == 0;
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

    /** The ACTIVE seed customer can also sign in with e-mail and password (ADR 0032): a new one each run. */
    private String customerPassword(String email) {
        String password = randomPassword();
        jdbc.update(
                "update customer_user set password_hash = ?, password_changed_at = ? where email = ?",
                encoder.encode(password),
                Timestamp.from(Instant.now(clock)),
                email);
        return password;
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
