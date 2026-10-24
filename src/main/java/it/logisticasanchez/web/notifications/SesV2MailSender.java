package it.logisticasanchez.web.notifications;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * SESv2 {@code SendEmail} from the verified domain (chapter 10 §2, ADR 0001): credentials from
 * the environment (the dedicated IAM user), region {@code ls.notifications.ses.region}. Built
 * only for the {@code ses} sink; no test calls it.
 */
public class SesV2MailSender implements MailSender {

    private final SesV2Client client;
    private final String from;

    public SesV2MailSender(SesV2Client client, String from) {
        this.client = client;
        this.from = from;
    }

    @Override
    public String send(OutboundEmail email) throws MailSendException {
        Body.Builder body = Body.builder().text(utf8(email.bodyText()));
        if (email.bodyHtml() != null) {
            body.html(utf8(email.bodyHtml()));
        }
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(
                        Destination.builder().toAddresses(email.recipient()).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(utf8(email.subject()))
                                .body(body.build())
                                .build())
                        .build());
        if (email.replyTo() != null) {
            request.replyToAddresses(email.replyTo());
        }
        try {
            SendEmailResponse response = client.sendEmail(request.build());
            return response.messageId();
        } catch (SdkException e) {
            throw new MailSendException("SES send failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static Content utf8(String value) {
        return Content.builder().data(value).charset("UTF-8").build();
    }
}
