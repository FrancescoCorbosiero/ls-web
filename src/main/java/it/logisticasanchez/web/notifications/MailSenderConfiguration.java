package it.logisticasanchez.web.notifications;

import it.logisticasanchez.web.platform.config.NotificationsProperties;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/** One {@link MailSender} bean per {@code ls.notifications.sink} value. */
@Configuration(proxyBeanMethods = false)
public class MailSenderConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ls.notifications.sink", havingValue = "memory")
    InMemoryMailSender inMemoryMailSender() {
        return new InMemoryMailSender();
    }

    @Bean
    @ConditionalOnProperty(name = "ls.notifications.sink", havingValue = "directory", matchIfMissing = true)
    DirectoryMailSender directoryMailSender(NotificationsProperties properties, Clock clock) {
        return new DirectoryMailSender(Path.of(properties.directory().path()), fromHeader(properties), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "ls.notifications.sink", havingValue = "ses")
    SesV2MailSender sesV2MailSender(NotificationsProperties properties, RecipientPolicy policy) {
        // RecipientPolicy is a parameter so the staging guard runs before any AWS client exists.
        SesV2Client client = SesV2Client.builder()
                .region(Region.of(properties.ses().region()))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        return new SesV2MailSender(client, fromHeader(properties));
    }

    static String fromHeader(NotificationsProperties properties) {
        return properties.fromName() + " <" + properties.fromAddress() + ">";
    }
}
