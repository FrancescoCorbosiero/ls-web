package it.logisticasanchez.web;

import it.logisticasanchez.web.auth.CreateStaffUserCommand;
import it.logisticasanchez.web.dev.DevSeedCommand;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of the Logistica Sanchez web app: public site, customer area, back office and
 * API in one deployable (chapter 10 §2). Modules live in sub-packages and talk to each other
 * only through their services. With the {@code create-staff-user} (ADM-01) or {@code dev-seed}
 * (local/test) argument the app runs that command instead of the server and exits.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        if (CreateStaffUserCommand.isRequested(args) || DevSeedCommand.isRequested(args)) {
            // The same application, but the server listens on an ephemeral port for the seconds
            // the command runs (the web beans the modules need exist only in a servlet context)
            // and no background job starts; the process exits with the command's code.
            String[] commandArgs = Stream.concat(
                            Stream.of(
                                    "--server.port=0",
                                    "--server.address=127.0.0.1",
                                    "--ls.notifications.worker.enabled=false",
                                    "--ls.retention.enabled=false"),
                            Stream.of(args))
                    .toArray(String[]::new);
            ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).run(commandArgs);
            if (DevSeedCommand.isRequested(args)
                    && context.getBeanProvider(DevSeedCommand.class).getIfAvailable() == null) {
                // The bean exists only under the local and test profiles (no seeded staff elsewhere).
                System.err.println("dev-seed è disponibile solo con il profilo local o test.");
                System.exit(SpringApplication.exit(context, () -> 2));
                return;
            }
            System.exit(SpringApplication.exit(context));
            return;
        }
        SpringApplication.run(Application.class, args);
    }
}
