package it.logisticasanchez.web.notifications;

/** One e-mail as handed to a {@link MailSender}: bodies with the token already substituted. */
public record OutboundEmail(
        long outboxId, String recipient, String replyTo, String subject, String bodyText, String bodyHtml) {}
