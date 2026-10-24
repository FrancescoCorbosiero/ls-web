package it.logisticasanchez.web.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.notifications.EmailOutbox;
import it.logisticasanchez.web.notifications.EmailOutboxRepository;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.RuleViolationException;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADM-09 and EML-07 (ADR 0016): a report for customer A holds no order of customer B; «solo
 * consegnati» filters; the office always receives EML-07, the customer only when chosen (with the
 * customer-area link), an address outside the allow-list is refused and nothing is stored or
 * queued; the audit row carries ids and counts, the addresses only in the detail; the download
 * needs a staff session or the customer's own session (another customer: 403; anonymous: the
 * sign-in redirect); the PDF carries the 13 legacy column headers and the customer block.
 */
@Tag("ADM-09")
@Tag("EML-07")
@SpringBootTest
@AutoConfigureMockMvc
class ReportIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ReportService reports;

    @Autowired
    private OrderService orders;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private EmailOutboxRepository outbox;

    @Autowired
    private BusinessProperties business;

    @Autowired
    private JdbcTemplate jdbc;

    private StaffUser operator;
    private CustomerTestData.Ids a;
    private CustomerTestData.Ids b;
    private String deliveredCode;
    private String openCode;
    private String bCode;

    @BeforeEach
    void data() {
        operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        a = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("report-a"), "ACTIVE", "Cliente A S.r.l.");
        b = CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("report-b"), "ACTIVE", "Cliente B S.r.l.");
        deliveredCode = approve(a, "DDT-A-1", "Dest A Uno", true);
        openCode = approve(a, "DDT-A-2", "Dest A Due", false);
        orders.submit(
                a.customerId(),
                a.customerUserId(),
                OrderTestData.draft("DDT-A-PENDING", "Dest A Tre", "Como", "22100"));
        bCode = approve(b, "DDT-B-1", "Dest B Uno", true);
        outbox.deleteAll();
    }

    private String approve(CustomerTestData.Ids customer, String ddt, String recipient, boolean delivered) {
        OrderView order = orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft(ddt, recipient, "Milano", "20121"));
        String code = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        orders.approve(order.id(), operator.getId(), code, "150,00");
        if (delivered) {
            orders.setTrackingState(order.id(), operator.getId(), 5);
        }
        return code;
    }

    private ReportService.Request request(boolean onlyDelivered, boolean sendToCustomer, String extra) {
        String today = LocalDate.now().toString();
        return new ReportService.Request(a.customerId(), today, today, onlyDelivered, sendToCustomer, extra);
    }

    @Test
    void theReportHoldsOnlyTheCustomersApprovedOrdersAndGoesToTheOfficeAlwaysAndToTheCustomerIfChosen() {
        ReportView all = reports.request(operator.getId(), request(false, false, ""));
        assertThat(all.rowCount()).isEqualTo(2);
        assertThat(all.recipients()).containsExactly(business.officeEmail().toLowerCase());
        List<EmailOutbox> mails = outbox.findAll();
        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getKind()).isEqualTo("EML-07");
        assertThat(mails.get(0).getRecipient()).isEqualTo(business.officeEmail());
        assertThat(mails.get(0).getSubject()).isEqualTo("Logistica Sanchez - Report ordini PDF");
        assertThat(mails.get(0).getBodyText())
                .contains("Date di riferimento: ")
                .contains("Puoi scaricare il report qui")
                .contains("Scarica PDF: " + business.siteUrl() + "/gestione/report/" + all.id() + "/download")
                .contains("Cliente A S.r.l.");
        outbox.deleteAll();

        ReportView delivered = reports.request(operator.getId(), request(true, true, ""));
        assertThat(delivered.rowCount()).isEqualTo(1);
        assertThat(delivered.recipients())
                .containsExactly(business.officeEmail().toLowerCase(), a.email());
        mails = outbox.findAll();
        assertThat(mails).hasSize(2);
        EmailOutbox toCustomer = mails.stream()
                .filter(m -> m.getRecipient().equals(a.email()))
                .findFirst()
                .orElseThrow();
        assertThat(toCustomer.getKind()).isEqualTo("EML-07");
        assertThat(toCustomer.getReplyTo()).isEqualTo(business.officeEmail());
        assertThat(toCustomer.getBodyText())
                .contains(business.siteUrl() + "/area-clienti/report/" + delivered.id() + "/download")
                .doesNotContain("/gestione/");

        assertThat(jdbc.queryForObject(
                        "select after::text from audit_log where action = 'report.send' and entity_id = ?",
                        String.class,
                        delivered.id()))
                .contains("\"rowCount\": 1")
                .contains("\"onlyDelivered\": true")
                .doesNotContain(a.email());
        assertThat(jdbc.queryForObject(
                        "select d.after::text from audit_log_detail d join audit_log l on l.id = d.audit_log_id"
                                + " where l.action = 'report.send' and l.entity_id = ?",
                        String.class,
                        delivered.id()))
                .contains(a.email());
    }

    @Test
    void anAddressOutsideTheAllowListIsRefusedAndNothingIsStoredOrQueued() {
        long runsBefore = jdbc.queryForObject("select count(*) from report_run", Long.class);
        assertThatThrownBy(() -> reports.request(operator.getId(), request(false, false, "estraneo@altrove.example")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("non è tra i destinatari autorizzati");
        assertThatThrownBy(() -> reports.request(operator.getId(), request(false, false, "non-un-indirizzo")))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> reports.request(
                        operator.getId(),
                        new ReportService.Request(
                                a.customerId(),
                                LocalDate.now().toString(),
                                LocalDate.now().plusDays(1).toString(),
                                false,
                                false,
                                "")))
                .hasMessageContaining("futuro");
        assertThatThrownBy(() -> reports.request(
                        operator.getId(), request(false, false, "").withCustomer(-1)))
                .isInstanceOf(RuleViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from report_run", Long.class))
                .isEqualTo(runsBefore);
        assertThat(outbox.findAll()).isEmpty();

        String allowed = "contabilita-" + UUID.randomUUID().toString().substring(0, 6) + "@ufficio.example";
        jdbc.update("insert into report_recipient (email, added_by) values (?, ?)", allowed, operator.getId());
        ReportView run = reports.request(
                operator.getId(), request(false, false, allowed.toUpperCase() + ", " + business.officeEmail()));
        assertThat(run.recipients()).containsExactly(business.officeEmail().toLowerCase(), allowed);
        assertThat(outbox.findAll())
                .extracting(EmailOutbox::getRecipient)
                .containsExactlyInAnyOrder(business.officeEmail(), allowed);
    }

    @Test
    void theDownloadNeedsTheRightSessionAndThePdfHasTheLegacyColumns() throws Exception {
        ReportView run = reports.request(operator.getId(), request(false, true, ""));
        MockHttpSession staff = StaffTestData.session(operator);
        byte[] pdf = mvc.perform(
                        get("/gestione/report/" + run.id() + "/download").session(staff))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(
                        header().string("Content-Disposition", org.hamcrest.Matchers.containsString("report-ordini-")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        for (String column : ReportPdf.COLUMNS) {
            assertThat(text).contains(column);
        }
        assertThat(text).contains("RPT-").contains("Sponda");
        text = text.replaceAll("\\s+", " ").replace("- ", "-");
        assertThat(text)
                .contains("I tuoi dati")
                .contains("Cliente A S.r.l.")
                .contains(a.email())
                .contains(deliveredCode)
                .contains(openCode)
                .contains("Dest A Uno")
                .contains("Sponda idraulica")
                .contains("DDT-A-1")
                .contains("150,00")
                .doesNotContain(bCode)
                .doesNotContain("Cliente B")
                .doesNotContain("DDT-A-PENDING")
                .doesNotContain("MB208");
        assertThat(new PdfReader(pdf).getPageSizeWithRotation(1).getWidth())
                .as("A4 landscape")
                .isGreaterThan(new PdfReader(pdf).getPageSizeWithRotation(1).getHeight());

        mvc.perform(get("/area-clienti/report/" + run.id() + "/download").session(CustomerTestData.session(a)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get("/area-clienti/report/" + run.id() + "/download").session(CustomerTestData.session(b)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/area-clienti/report/" + run.id() + "/download")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/gestione/report/" + run.id() + "/download")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/gestione/report/" + run.id() + "/download").session(CustomerTestData.session(a)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/gestione/report/999999999/download").session(staff)).andExpect(status().isNotFound());
        assertThatThrownBy(() -> reports.pdfForCustomer(run.id(), b.customerId()))
                .isInstanceOf(ClientErrorException.class);
    }

    @Test
    void theReportPageSendsAndListsTheRuns() throws Exception {
        MockHttpSession staff = StaffTestData.session(operator);
        String today = LocalDate.now().toString();
        mvc.perform(post("/gestione/report/")
                        .session(staff)
                        .with(csrf())
                        .param("cliente", Long.toString(a.customerId()))
                        .param("da", today)
                        .param("a", today)
                        .param("soloConsegnati", "1"))
                .andExpect(status().is3xxRedirection());
        String page = mvc.perform(get("/gestione/report/").session(staff))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page).contains("Invia Report").contains("Solo consegnati").contains("/download");
        String refused = mvc.perform(post("/gestione/report/")
                        .session(staff)
                        .with(csrf())
                        .param("cliente", Long.toString(a.customerId()))
                        .param("da", today)
                        .param("a", today)
                        .param("destinatari", "estraneo@altrove.example"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(refused).contains("data-error").contains("non è tra i destinatari autorizzati");
    }
}
