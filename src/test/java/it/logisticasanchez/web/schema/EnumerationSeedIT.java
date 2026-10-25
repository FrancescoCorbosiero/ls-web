package it.logisticasanchez.web.schema;

import static org.assertj.core.api.Assertions.assertThat;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADM-10: the enumerations are seeded with exactly the legacy values — the six tracking states
 * and the six accessory services of chapter 02 §9 / 12b §2.2 (ids, names, labels, order; item 0
 * of the services is "none").
 */
@Tag("ADM-10")
@SpringBootTest
class EnumerationSeedIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exactlyTheSixLegacyTrackingStatesInDeclarationOrder() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, label, sort_order, step_label from tracking_state order by sort_order");

        assertThat(rows).hasSize(6);
        assertThat(rows.stream().map(row -> List.of(row.get("id"), row.get("name"), row.get("label"))))
                .containsExactly(
                        List.of(0, "NON_GESTITO", "Non gestito"),
                        List.of(1, "PRESA", "Presa in carico"),
                        List.of(2, "TRANSITO", "Transito"),
                        List.of(3, "TERMINAL_DEST", "Terminal destinatario"),
                        List.of(4, "ASSEGNATO", "Assegnato"),
                        List.of(5, "CONSEGNATO", "Consegnato"));
        assertThat(rows.stream().map(row -> row.get("sort_order")))
                .as("sort order equals the legacy id")
                .containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(rows.get(0).get("step_label"))
                .as("NON_GESTITO has no progress step")
                .isNull();
        assertThat(rows.subList(1, 6))
                .allSatisfy(row -> assertThat(row.get("step_label")).isNotNull());
    }

    @Test
    void exactlyTheSixLegacyAccessoryServicesWithItemZeroAsNone() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, label, is_none, active, sort_order from accessory_service order by sort_order");

        assertThat(rows).hasSize(6);
        assertThat(rows.stream().map(row -> List.of(row.get("id"), row.get("name"), row.get("label"))))
                .containsExactly(
                        List.of(0, "NESSUN_SERVIZIO", "Nessun servizio accessorio"),
                        List.of(1, "PRENOTAZIONE_TEL", "Prenotazione telefonica"),
                        List.of(2, "SPONDA_IDR", "Sponda idraulica"),
                        List.of(3, "MERCE_LUNGA", "Segnalazione merce lunga"),
                        List.of(4, "FACCHINAGGIO", "Facchinaggio"),
                        List.of(5, "GDO", "GDO"));
        assertThat(rows.stream().map(row -> row.get("is_none")))
                .as("only item 0 is the \"none\" entry")
                .containsExactly(true, false, false, false, false, false);
        assertThat(rows.stream().map(row -> row.get("active"))).containsOnly(true);
        assertThat(rows.stream().map(row -> row.get("sort_order"))).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void servicesAddedLaterContinueAfterTheLegacyIds() {
        Long next =
                jdbc.queryForObject("select nextval(pg_get_serial_sequence('accessory_service', 'id'))", Long.class);
        try {
            assertThat(next).as("ENH-14 rows start at id 6").isEqualTo(6L);
        } finally {
            // nextval consumed a value: put the sequence back where the seed left it.
            jdbc.execute("alter table accessory_service alter column id restart with 6");
        }
    }
}
