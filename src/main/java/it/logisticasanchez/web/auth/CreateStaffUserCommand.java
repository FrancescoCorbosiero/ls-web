package it.logisticasanchez.web.auth;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * {@code create-staff-user --username=… --role=ADMIN|OPERATOR [--name=…] [--email=…]}
 * (ADM-01, chapter 08 §3.9): creates the account without a password and prints, once, the
 * one-time set-password link (24 hours). Run as {@code java -jar app.jar create-staff-user …} or
 * {@code ./mvnw spring-boot:run -Dspring-boot.run.arguments=create-staff-user,--username=…,--role=ADMIN};
 * {@link it.logisticasanchez.web.Application#main} starts the app without the web server and
 * exits with this runner's code. No password is ever read from the arguments or printed.
 *
 * <p>With {@code --ask-password} the password is typed at the terminal instead (twice, not echoed,
 * the same policy as the link's page), for a first ADMIN created before the domain reaches the
 * server, when the link cannot be opened yet; nothing is printed but the outcome.
 */
@Component
public class CreateStaffUserCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String NAME = "create-staff-user";

    /** Reads passwords from the terminal without echo. */
    interface PasswordReader {
        /** Whether a terminal is attached. */
        boolean available();

        /** One password; null at the end of the input. */
        String read(String prompt);
    }

    private static final PasswordReader CONSOLE = new PasswordReader() {
        @Override
        public boolean available() {
            return System.console() != null;
        }

        @Override
        public String read(String prompt) {
            char[] typed = System.console().readPassword("%s", prompt);
            return typed == null ? null : new String(typed);
        }
    };

    private static final int ATTEMPTS = 3;

    private final StaffAccountService accounts;
    private volatile int exitCode;

    public CreateStaffUserCommand(StaffAccountService accounts) {
        this.accounts = accounts;
    }

    public static boolean isRequested(String... args) {
        return List.of(args).contains(NAME);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().contains(NAME)) {
            return;
        }
        exitCode = run(args, System.out, System.err, CONSOLE);
    }

    /** Runs the command and returns the exit code; package-visible so the test captures the output. */
    int run(ApplicationArguments args, PrintStream out, PrintStream err) {
        return run(args, out, err, CONSOLE);
    }

    int run(ApplicationArguments args, PrintStream out, PrintStream err, PasswordReader reader) {
        String username = option(args, "username");
        String roleName = option(args, "role");
        if (username == null || roleName == null) {
            err.println(
                    "Uso: create-staff-user --username=<username> --role=ADMIN|OPERATOR [--name=<nome>] [--email=<e-mail>]"
                            + " [--ask-password]");
            return 2;
        }
        StaffRole role;
        try {
            role = StaffRole.valueOf(roleName.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            err.println("Ruolo non valido: " + roleName + " (ammessi: ADMIN, OPERATOR)");
            return 2;
        }
        boolean askPassword = args.containsOption("ask-password");
        if (askPassword && !reader.available()) {
            err.println("--ask-password chiede un terminale: con Docker usa  docker compose run --rm app …  (non -T).");
            return 2;
        }
        try {
            StaffAccountService.SetupLink link =
                    accounts.create(username, option(args, "name"), option(args, "email"), role);
            out.println("Utente " + link.username() + " creato con ruolo " + role + ".");
            if (askPassword) {
                return askPassword(link, out, err, reader);
            }
            out.println("Link per impostare la password (valido 24 ore, utilizzabile una volta):");
            out.println(accounts.setupUrl(link.rawToken()));
            return 0;
        } catch (StaffAccountService.UsernameTakenException e) {
            err.println("Username già in uso: " + StaffUser.normaliseUsername(username));
            return 1;
        }
    }

    /** Up to three attempts; a refused or mismatched password asks again, the link stays usable. */
    private int askPassword(
            StaffAccountService.SetupLink link, PrintStream out, PrintStream err, PasswordReader reader) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String password = reader.read("Password (almeno 12 caratteri): ");
            String again = reader.read("Ripeti la password: ");
            if (password == null || again == null) {
                break;
            }
            if (!password.equals(again)) {
                err.println("Le due password non coincidono.");
                continue;
            }
            try {
                accounts.setPassword(link.rawToken(), password);
                out.println("Password impostata: accedi da /gestione/ con " + link.username() + ".");
                return 0;
            } catch (StaffAccountService.PasswordRefusedException e) {
                err.println(
                        e.violation() == PasswordPolicy.Violation.TOO_SHORT
                                ? "La password deve avere almeno 12 caratteri."
                                : "Questa password è troppo comune: scegline un'altra.");
            }
        }
        out.println("Password non impostata. Link per impostarla (valido 24 ore, utilizzabile una volta):");
        out.println(accounts.setupUrl(link.rawToken()));
        return 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() || values.get(0).isBlank()
                ? null
                : values.get(0).strip();
    }
}
