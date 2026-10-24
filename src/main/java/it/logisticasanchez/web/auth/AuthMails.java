package it.logisticasanchez.web.auth;

import it.logisticasanchez.web.notifications.EmailMessage;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import java.time.Duration;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * The e-mails the auth module queues (EML-01 sign-in, registration and e-mail-change links and
 * the e-mail-change notice, EML-S1 staff set-password link): Italian texts from the message bundle ({@code mail.*}), a plain-text and a
 * minimal HTML body, the link carrying {@link EmailMessage#TOKEN_PLACEHOLDER} that the outbox
 * worker replaces with the raw token. The branded templates of ADR 0015 arrive with the Phase 2
 * {@code notifications} module.
 */
@Component
public class AuthMails {

    static final String KIND_CUSTOMER_LINK = "EML-01";
    static final String KIND_STAFF_SET_PASSWORD = "EML-S1";

    private final MessageSource messages;
    private final BusinessProperties business;

    public AuthMails(MessageSource messages, BusinessProperties business) {
        this.messages = messages;
        this.business = business;
    }

    /** EML-01 for a known customer user: the sign-in link, valid {@code validity}. */
    EmailMessage signIn(String recipient, Duration validity) {
        String link = business.siteUrl() + AuthRoutes.CUSTOMER_LINK + "?t=" + EmailMessage.TOKEN_PLACEHOLDER;
        return message(KIND_CUSTOMER_LINK, recipient, "mail.signin", link, minutes(validity));
    }

    /** EML-01 for an unknown e-mail: the registration link, valid {@code validity}. */
    EmailMessage register(String recipient, Duration validity) {
        String link = business.siteUrl() + AuthRoutes.CUSTOMER_REGISTRATION + "?t=" + EmailMessage.TOKEN_PLACEHOLDER;
        return message(KIND_CUSTOMER_LINK, recipient, "mail.register", link, hours(validity));
    }

    /** EML-01 for an e-mail change (CUS-06): the confirmation link sent to the new address. */
    EmailMessage changeEmail(String newAddress, Duration validity) {
        String link = business.siteUrl() + AuthRoutes.CUSTOMER_CHANGE_EMAIL + "?t=" + EmailMessage.TOKEN_PLACEHOLDER;
        return message(KIND_CUSTOMER_LINK, newAddress, "mail.changeEmail", link, minutes(validity));
    }

    /** EML-01 notice to the current address that a change to {@code newAddress} was requested. */
    EmailMessage changeEmailNotice(String currentAddress, String newAddress) {
        String link = business.siteUrl() + AuthRoutes.CUSTOMER_AREA;
        return message(KIND_CUSTOMER_LINK, currentAddress, "mail.changeEmailNotice", link, newAddress)
                .withReplyTo(business.officeEmail());
    }

    /** EML-S1: the set-password link of a staff account created by an ADMIN. */
    EmailMessage staffSetPassword(String recipient, String displayName, String username, Duration validity) {
        String link = business.siteUrl() + AuthRoutes.STAFF_SET_PASSWORD + "?t=" + EmailMessage.TOKEN_PLACEHOLDER;
        return message(
                KIND_STAFF_SET_PASSWORD,
                recipient,
                "mail.staffSetPassword",
                link,
                hours(validity),
                displayName,
                username);
    }

    private EmailMessage message(String kind, String recipient, String key, String link, Object... args) {
        Object[] bodyArgs = new Object[args.length + 2];
        bodyArgs[0] = link;
        bodyArgs[1] = business.brandName();
        System.arraycopy(args, 0, bodyArgs, 2, args.length);
        String subject = text(key + ".subject", business.brandName());
        String intro = text(key + ".intro", bodyArgs);
        String outro = text(key + ".outro", bodyArgs);
        String action = text(key + ".action");
        String signature = business.brandName();
        String bodyText = intro + "\n\n" + link + "\n\n" + outro + "\n\n" + signature + "\n";
        String bodyHtml = "<!DOCTYPE html><html lang=\"it\"><body style=\"font-family:sans-serif\">"
                + "<p>" + escape(intro) + "</p>"
                + "<p><a href=\"" + link + "\">" + escape(action) + "</a></p>"
                + "<p>" + escape(outro) + "</p>"
                + "<p>" + escape(signature) + "</p>"
                + "</body></html>";
        return EmailMessage.of(kind, recipient, subject, bodyText, bodyHtml);
    }

    private String text(String key, Object... args) {
        return messages.getMessage(key, args, Locale.ITALIAN);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }

    private static long minutes(Duration duration) {
        return Math.max(1, duration.toMinutes());
    }

    private static long hours(Duration duration) {
        return Math.max(1, duration.toHours());
    }
}
