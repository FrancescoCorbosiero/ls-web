package it.logisticasanchez.web.platform.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The web app manifests, served with the {@code application/manifest+json} type that the
 * default static handler does not know: {@code /site.webmanifest} of the static site (PUB-02,
 * the copied file) and {@code /area-clienti.webmanifest}, which makes the customer area
 * installable on a phone (ADR 0047). Both are public and cookie-free: browsers fetch manifests
 * without credentials.
 */
@RestController
public class WebManifestController {

    public static final String PATH = "/site.webmanifest";
    public static final String CUSTOMER_AREA_PATH = "/area-clienti.webmanifest";
    private static final MediaType MANIFEST = MediaType.parseMediaType("application/manifest+json");
    private static final Resource FILE = new ClassPathResource("static/site.webmanifest");
    private static final Resource CUSTOMER_AREA_FILE = new ClassPathResource("static/area-clienti.webmanifest");

    @GetMapping(PATH)
    public ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok().contentType(MANIFEST).body(FILE);
    }

    @GetMapping(CUSTOMER_AREA_PATH)
    public ResponseEntity<Resource> customerAreaManifest() {
        return ResponseEntity.ok().contentType(MANIFEST).body(CUSTOMER_AREA_FILE);
    }
}
