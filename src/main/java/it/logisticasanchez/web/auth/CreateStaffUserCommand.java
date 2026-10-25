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
 */
@Component
public class CreateStaffUserCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String NAME = "create-staff-user";

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
        exitCode = run(args, System.out, System.err);
    }

    /** Runs the command and returns the exit code; package-visible so the test captures the output. */
    int run(ApplicationArguments args, PrintStream out, PrintStream err) {
        String username = option(args, "username");
        String roleName = option(args, "role");
        if (username == null || roleName == null) {
            err.println(
                    "Uso: create-staff-user --username=<username> --role=ADMIN|OPERATOR [--name=<nome>] [--email=<e-mail>]");
            return 2;
        }
        StaffRole role;
        try {
            role = StaffRole.valueOf(roleName.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            err.println("Ruolo non valido: " + roleName + " (ammessi: ADMIN, OPERATOR)");
            return 2;
        }
        try {
            StaffAccountService.SetupLink link =
                    accounts.create(username, option(args, "name"), option(args, "email"), role);
            out.println("Utente " + link.username() + " creato con ruolo " + role + ".");
            out.println("Link per impostare la password (valido 24 ore, utilizzabile una volta):");
            out.println(accounts.setupUrl(link.rawToken()));
            return 0;
        } catch (StaffAccountService.UsernameTakenException e) {
            err.println("Username già in uso: " + StaffUser.normaliseUsername(username));
            return 1;
        }
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
