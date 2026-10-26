package it.logisticasanchez.web.tracking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/** ENH-14 tests: the lists a page shows, and the seeded enumeration put back after a test changed it. */
final class AccessoryServicesTestSupport {

    /** «Servizi accessori» of the ported /servizi/, in order. */
    static final List<String> SITE = List.of(
            "Sponda idraulica",
            "Consegna su appuntamento",
            "Preavviso al destinatario",
            "Ritiri programmati",
            "Prova di consegna digitale",
            "Copertura assicurativa integrativa");

    /** The extras of the ported quote wizard, in order: their values (PUB-06). */
    static final List<String> EXTRAS = List.of(
            "Sponda idraulica",
            "Consegna su appuntamento",
            "Preavviso al destinatario",
            "Merce fragile o non sovrapponibile");

    /** EP-20 in M1 (API-02, FX-12). */
    static final String LEGACY_JSON =
            "[{\"id\":0,\"name\":\"NESSUN_SERVIZIO\",\"label\":\"Nessun servizio accessorio\"},"
                    + "{\"id\":1,\"name\":\"PRENOTAZIONE_TEL\",\"label\":\"Prenotazione telefonica\"},"
                    + "{\"id\":2,\"name\":\"SPONDA_IDR\",\"label\":\"Sponda idraulica\"},"
                    + "{\"id\":3,\"name\":\"MERCE_LUNGA\",\"label\":\"Segnalazione merce lunga\"},"
                    + "{\"id\":4,\"name\":\"FACCHINAGGIO\",\"label\":\"Facchinaggio\"},"
                    + "{\"id\":5,\"name\":\"GDO\",\"label\":\"GDO\"}]";

    private static final Pattern H3 = Pattern.compile("<h3>([^<]*)</h3>");
    private static final Pattern ICON = Pattern.compile("/img/icons\\.svg#(i-[a-z0-9-]+)");
    private static final Pattern EXTRA = Pattern.compile("name=\"servizi_extra\\[\\]\" value=\"([^\"]*)\"");
    private static final Pattern OPTION = Pattern.compile("<option[^>]*>([^<]*)</option>");

    private AccessoryServicesTestSupport() {}

    /** The section «Servizi accessori» of /servizi/. */
    static String accessorySection(String page) {
        int from = page.indexOf("id=\"accessori\"");
        return page.substring(from, page.indexOf("</section>", from));
    }

    static List<String> siteLabels(String page) {
        return all(H3, accessorySection(page));
    }

    static List<String> siteIcons(String page) {
        return all(ICON, accessorySection(page));
    }

    static List<String> quoteExtras(String page) {
        return all(EXTRA, page);
    }

    /** The labels of the accessory-service select of an order form. */
    static List<String> orderChoices(String page) {
        int from = page.indexOf("id=\"accessoryServiceId\"");
        return all(OPTION, page.substring(from, page.indexOf("</select>", from)));
    }

    static int id(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("select id from accessory_service where name = ?", Integer.class, name);
    }

    /**
     * Puts V009 and V023 back: removes the rows the tests added (and their use by orders or saved
     * quotes), then gives rows 1–11 their seeded labels, places, state and order.
     */
    static void restoreSeeds(JdbcTemplate jdbc) {
        jdbc.update("update shipment_order set accessory_service_id = null where accessory_service_id > 11");
        jdbc.update("update quote set accessory_service_id = null where accessory_service_id > 11");
        jdbc.update("delete from accessory_service where id > 11");
        jdbc.update("update accessory_service set sort_order = -1000 - id where id between 1 and 11");
        Object[][] seeds = {
            {"PRENOTAZIONE_TEL", "Prenotazione telefonica", 1, true, false, false},
            {"SPONDA_IDR", "Sponda idraulica", 2, true, true, true},
            {"MERCE_LUNGA", "Segnalazione merce lunga", 3, true, false, false},
            {"FACCHINAGGIO", "Facchinaggio", 4, true, false, false},
            {"GDO", "GDO", 5, true, false, false},
            {"CONSEGNA_APPUNTAMENTO", "Consegna su appuntamento", 6, false, true, true},
            {"PREAVVISO_DESTINATARIO", "Preavviso al destinatario", 7, false, true, true},
            {"RITIRI_PROGRAMMATI", "Ritiri programmati", 8, false, true, false},
            {"PROVA_CONSEGNA_DIGITALE", "Prova di consegna digitale", 9, false, true, false},
            {"COPERTURA_ASSICURATIVA", "Copertura assicurativa integrativa", 10, false, true, false},
            {"MERCE_FRAGILE", "Merce fragile o non sovrapponibile", 11, false, false, true},
        };
        for (Object[] seed : seeds) {
            jdbc.update(
                    "update accessory_service set label = ?, sort_order = ?, in_orders = ?, on_site = ?, in_quote = ?,"
                            + " active = true where name = ?",
                    seed[1],
                    seed[2],
                    seed[3],
                    seed[4],
                    seed[5],
                    seed[0]);
        }
        jdbc.update(
                "update accessory_service set description = ?, icon = 'i-arrow-down-to-line', quote_hint = ?"
                        + " where name = 'SPONDA_IDR'",
                "Il pallet viene calato a terra con la pedana del mezzo: indispensabile se a destinazione non ci sono"
                        + " muletto o banchina di carico.",
                "A destinazione non c'è un muletto");
    }

    private static List<String> all(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1).replace("&#39;", "'").replace("&amp;", "&"));
        }
        return found;
    }
}
