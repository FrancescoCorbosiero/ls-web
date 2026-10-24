package it.logisticasanchez.web.platform.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /site.webmanifest} of the static site (PUB-02): the copied file, served with the
 * {@code application/manifest+json} type that the default static handler does not know.
 */
@RestController
public class WebManifestController {

    public static final String PATH = "/site.webmanifest";
    private static final MediaType MANIFEST = MediaType.parseMediaType("application/manifest+json");
    private static final Resource FILE = new ClassPathResource("static/site.webmanifest");

    @GetMapping(PATH)
    public ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok().contentType(MANIFEST).body(FILE);
    }
}
