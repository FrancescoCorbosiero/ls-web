package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.site.forms.FormSubmission;
import it.logisticasanchez.web.site.forms.LeadStatus;
import it.logisticasanchez.web.site.forms.LeadView;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ENH-06 (ADR 0036): what «Crea cliente» and «Crea ordine» carry over from a website request. */
@Tag("ENH-06")
class LeadDraftsTest {

    private static final List<TrackingCatalog.ServiceView> SERVICES = List.of(
            new TrackingCatalog.ServiceView(0, "Nessun servizio accessorio", true),
            new TrackingCatalog.ServiceView(1, "Prenotazione telefonica", false),
            new TrackingCatalog.ServiceView(2, "Sponda idraulica", false));

    @Test
    void anEpalQuoteBecomesOneLineWithTheWeightOfEachPallet() {
        LeadView lead = quote("eur", "2", "601", "150.4", List.of("Sponda idraulica", "Preavviso al destinatario"));

        assertThat(LeadDrafts.line(lead))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "kind", "EPAL",
                        "lengthCm", "120",
                        "widthCm", "80",
                        "heightCm", "150",
                        "quantity", "2",
                        "weightKg", "301"));
        assertThat(LeadDrafts.accessoryService(lead, SERVICES)).isEqualTo(2);
        assertThat(LeadDrafts.notes(lead)).isEqualTo("Ricambi");
        assertThat(LeadDrafts.recipient(lead).city()).isEqualTo("Torino");
        assertThat(LeadDrafts.recipient(lead).postalCode()).isEqualTo("10121");
        assertThat(LeadDrafts.recipient(lead).name()).isNull();
    }

    @Test
    void otherFormatsAndFractionalQuantitiesLeaveTheRestToStaff() {
        assertThat(LeadDrafts.line(quote("quarto", "2.5", "300", null, List.of())))
                .containsExactlyInAnyOrderEntriesOf(Map.of("kind", "GENERIC", "lengthCm", "60", "widthCm", "40"));
        assertThat(LeadDrafts.line(quote("industriale", "20", "4000", null, List.of())))
                .containsEntry("lengthCm", "120")
                .containsEntry("widthCm", "100")
                .containsEntry("quantity", "20")
                .containsEntry("weightKg", "200");
        assertThat(LeadDrafts.line(quote("altro", "1", "50", "90", List.of())))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("kind", "GENERIC", "heightCm", "90", "quantity", "1", "weightKg", "50"));
        assertThat(LeadDrafts.accessoryService(
                        quote("eur", "1", "1", null, List.of("Preavviso al destinatario")), SERVICES))
                .isNull();
        assertThat(LeadDrafts.line(contact())).isEmpty();
    }

    @Test
    void theCustomerIsTheCompanyOrThePersonAtThePickUpPlace() {
        assertThat(LeadDrafts.customer(quote("eur", "1", "1", null, List.of())))
                .containsEntry("companyName", "Officina Prova S.r.l.")
                .containsEntry("city", "Seregno")
                .containsEntry("postalCode", "20831")
                .containsEntry("phone", "+39 333 000 0001")
                .containsEntry("addressLine", "")
                .containsEntry("province", "");
        assertThat(LeadDrafts.customer(contact()))
                .containsEntry("companyName", "Lucia Esempio")
                .containsEntry("city", "")
                .containsEntry("phone", "");
    }

    private static LeadView quote(String format, String quantity, String weight, String height, List<String> extras) {
        return new LeadView(
                1,
                FormSubmission.Form.PREVENTIVO,
                LeadStatus.NEW,
                Instant.parse("2026-10-26T08:00:00Z"),
                "Mario Prova",
                "Officina Prova S.r.l.",
                "richiesta@cliente.example",
                "+39 333 000 0001",
                "telefono",
                null,
                null,
                "pallet",
                null,
                "20831",
                "Seregno",
                "10121",
                "Torino",
                "azienda",
                format,
                new BigDecimal(quantity),
                new BigDecimal(weight),
                height == null ? null : new BigDecimal(height),
                "Ricambi",
                extras,
                null,
                null);
    }

    private static LeadView contact() {
        return new LeadView(
                2,
                FormSubmission.Form.CONTATTI,
                LeadStatus.NEW,
                Instant.parse("2026-10-26T08:00:00Z"),
                "Lucia Esempio",
                null,
                "contatto@cliente.example",
                null,
                null,
                "Magazzino",
                "Vorrei informazioni sullo stoccaggio.",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null);
    }
}
