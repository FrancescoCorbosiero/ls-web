package it.logisticasanchez.web.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.logisticasanchez.web.support.AbstractPostgresIT;
import it.logisticasanchez.web.support.CustomerTestData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-01 with the owner request of 2026-10-26 (ADR 0047): the customer area installs on a phone
 * as an app. Its manifest is public and cookie-free and describes a standalone app scoped to
 * {@code /area-clienti/}, with icons of 192 and 512 pixels (one maskable) served at the declared
 * sizes; every customer-area page links it with the iPhone metas, the back office and the public
 * site do not; the orders page carries the instructions for adding it to the home screen and,
 * for a customer without a password, the way to set one (the app signs in with e-mail and
 * password).
 */
@Tag("NFR-01")
@SpringBootTest
@AutoConfigureMockMvc
class InstallableCustomerAreaIT extends AbstractPostgresIT {

    private static final String MANIFEST_LINK = "<link rel=\"manifest\" href=\"/area-clienti.webmanifest\">";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theManifestDescribesAStandaloneAppScopedToTheCustomerAreaWithItsIcons() throws Exception {
        byte[] body = mvc.perform(get("/area-clienti.webmanifest"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/manifest+json"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        JsonNode manifest = json.readTree(body);
        assertThat(manifest.get("id").asString()).isEqualTo("/area-clienti/");
        assertThat(manifest.get("start_url").asString()).isEqualTo("/area-clienti/");
        assertThat(manifest.get("scope").asString()).isEqualTo("/area-clienti/");
        assertThat(manifest.get("display").asString()).isEqualTo("standalone");
        assertThat(manifest.get("name").asString()).isEqualTo("Logistica Sanchez – Area clienti");
        assertThat(manifest.get("short_name").asString())
                .as("fits under the icon on a home screen")
                .hasSizeLessThanOrEqualTo(12);
        assertThat(manifest.get("lang").asString()).isEqualTo("it");

        Set<String> sizes = new HashSet<>();
        boolean maskable = false;
        for (JsonNode icon : manifest.get("icons")) {
            byte[] png = mvc.perform(get(icon.get("src").asString()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/png"))
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            String declared = icon.get("sizes").asString();
            assertThat(image.getWidth() + "x" + image.getHeight())
                    .as(icon.toString())
                    .isEqualTo(declared);
            sizes.add(declared);
            maskable |= icon.has("purpose") && icon.get("purpose").asString().contains("maskable");
        }
        assertThat(sizes).contains("192x192", "512x512");
        assertThat(maskable).as("a maskable icon for Android's shapes").isTrue();
    }

    @Test
    void theCustomerAreaPagesLinkTheManifestAndTheIphoneMetasTheBackOfficeAndTheSiteDoNot() throws Exception {
        assertThat(html(get("/area-clienti/")))
                .contains(MANIFEST_LINK)
                .contains("<meta name=\"mobile-web-app-capable\" content=\"yes\">")
                .contains("<meta name=\"apple-mobile-web-app-capable\" content=\"yes\">")
                .contains("<meta name=\"apple-mobile-web-app-title\" content=\"LS Clienti\">");
        assertThat(html(get("/gestione/"))).doesNotContain("webmanifest").doesNotContain("apple-mobile-web-app");
        assertThat(html(get("/"))).contains("/site.webmanifest").doesNotContain("/area-clienti.webmanifest");
    }

    @Test
    void theOrdersPageExplainsHowToAddTheAreaToTheHomeScreenAndOffersAPassword() throws Exception {
        CustomerTestData.Ids customer =
                CustomerTestData.customerUser(jdbc, CustomerTestData.uniqueEmail("app"), "ACTIVE");

        String page = html(get("/area-clienti/ordini/").session(CustomerTestData.session(customer)));

        assertThat(page).contains(MANIFEST_LINK);
        int start = page.indexOf("data-install-hint");
        assertThat(start).as("the install hint on the orders page").isPositive();
        String hint = page.substring(start, page.indexOf("</details>", start));
        assertThat(hint)
                .contains("Aggiungi alla schermata Home")
                .contains("Installa app")
                .contains("href=\"/area-clienti/profilo/#password\"");
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.accept("text/html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
