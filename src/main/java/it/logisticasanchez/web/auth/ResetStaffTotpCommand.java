package it.logisticasanchez.web.auth;

import java.io.PrintStream;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * {@code reset-staff-totp --username=…} (ENH-09, ADR 0039): removes a staff user's two-factor
 * secret from the server, for an ADMIN who lost the phone and cannot open «Utenti della
 * gestione»; at the next login (flag on) the user sets the app up again. Run as
 * {@code java -jar app.jar reset-staff-totp --username=…}; audited as a system action.
 */
@Component
public class ResetStaffTotpCommand implements ApplicationRunner, ExitCodeGenerator {

    public static final String NAME = "reset-staff-totp";

    private final StaffTwoFactorService twoFactor;
    private volatile int exitCode;

    public ResetStaffTotpCommand(StaffTwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
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
        List<String> values = args.getOptionValues("username");
        String username = values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
        if (username == null) {
            err.println("Uso: reset-staff-totp --username=<username>");
            return 2;
        }
        if (!twoFactor.resetByCommand(username)) {
            err.println("Nessun utente con username " + StaffUser.normaliseUsername(username));
            return 1;
        }
        out.println("Verifica in due passaggi azzerata per " + StaffUser.normaliseUsername(username)
                + ": al prossimo accesso la configura di nuovo.");
        return 0;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
