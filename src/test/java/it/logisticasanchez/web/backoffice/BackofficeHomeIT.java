package it.logisticasanchez.web.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.auth.StaffRole;
import it.logisticasanchez.web.auth.StaffUser;
import it.logisticasanchez.web.auth.StaffUserRepository;
import it.logisticasanchez.web.customers.CustomerService;
import it.logisticasanchez.web.orders.OrderService;
import it.logisticasanchez.web.orders.OrderView;
import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import it.logisticasanchez.web.support.OrderTestData;
import it.logisticasanchez.web.support.StaffTestData;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The back-office home «Panoramica» (ADR 0029) that staff land on after signing in (ADM-01): the
 * queues of ADM-02 and ADM-04 with their counts, the shipments still travelling with a link to the
 * order list filtered on the undelivered ones (ADM-05), the shortcuts to a new order and a new
 * customer, the latest orders.
 */
@Tag("ADM-01")
@Tag("ADM-02")
@Tag("ADM-04")
@Tag("ADM-05")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeHomeIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffUserRepository staffUsers;

    @Autowired
    private OrderService orders;

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theHomeShowsWhatWaitsForStaff() throws Exception {
        StaffUser operator = StaffTestData.staff(staffUsers, StaffRole.OPERATOR);
        MockHttpSession session = StaffTestData.session(operator);
        CustomerTestData.Ids customer = CustomerTestData.customerUser(
                jdbc, CustomerTestData.uniqueEmail("panoramica"), "ACTIVE", "Mittente Panoramica S.r.l.");
        CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("in-attesa"), "PENDING_APPROVAL");
        OrderView waiting = order(customer, "DDT-HOME-1");
        String travelling = "TEST-" + suffix();
        orders.approve(order(customer, "DDT-HOME-2").id(), operator.getId(), travelling, "100");
        String delivered = "TEST-" + suffix();
        OrderView done = order(customer, "DDT-HOME-3");
        orders.approve(done.id(), operator.getId(), delivered, "100");
        orders.setTrackingState(done.id(), operator.getId(), 5);

        String home = html("/gestione/", session);
        assertThat(tile(home, "orders"))
                .isEqualTo(orders.countPendingApproval())
                .isPositive();
        assertThat(tile(home, "customers"))
                .isEqualTo(customers.countPendingApproval())
                .isPositive();
        assertThat(tile(home, "transit")).isEqualTo(orders.countInTransit()).isPositive();
        assertThat(home)
                .contains("href=\"/gestione/ordini/in-approvazione/\"")
                .contains("href=\"/gestione/clienti/in-approvazione/\"")
                .contains("href=\"/gestione/ordini/?stato=APPROVED&amp;tracking=in-corso\"")
                .contains("href=\"/gestione/ordini/nuovo\"")
                .contains("href=\"/gestione/clienti/nuovo\"")
                .contains("data-table=\"recent-orders\"")
                .contains("data-order=\"" + delivered + "\"")
                .contains("aspettano te");

        // the travelling shipments: approved and not delivered
        assertThat(html("/gestione/ordini/?stato=APPROVED&tracking=in-corso&codice=" + travelling, session))
                .contains("data-order=\"" + travelling + "\"")
                .contains("value=\"in-corso\" selected=\"selected\"");
        assertThat(html("/gestione/ordini/?stato=APPROVED&tracking=in-corso&codice=" + delivered, session))
                .doesNotContain("data-order=\"" + delivered + "\"");
        assertThat(html("/gestione/ordini/?stato=APPROVED&codice=" + delivered, session))
                .contains("data-order=\"" + delivered + "\"");
        assertThat(waiting.isPending()).isTrue();
    }

    private OrderView order(CustomerTestData.Ids customer, String ddt) {
        return orders.submit(
                customer.customerId(),
                customer.customerUserId(),
                OrderTestData.draft(ddt, "Destinatario Panoramica", "Lodi", "26900"));
    }

    private static long tile(String html, String name) {
        Matcher m = Pattern.compile(
                        "data-tile=\"" + name + "\">\\s*<span class=\"tile-top\"><span class=\"tile-value\">(\\d+)<")
                .matcher(html);
        assertThat(m.find()).as("tile %s", name).isTrue();
        return Long.parseLong(m.group(1));
    }

    private String html(String path, MockHttpSession session) throws Exception {
        return mvc.perform(get(path).session(session).accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
