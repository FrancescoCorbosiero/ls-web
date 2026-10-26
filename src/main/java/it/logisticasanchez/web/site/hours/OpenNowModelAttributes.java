package it.logisticasanchez.web.site.hours;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code openNow} for the public pages of the site module (ENH-18): the openings JSON of the next days, or null. */
@ControllerAdvice(basePackages = "it.logisticasanchez.web.site")
public class OpenNowModelAttributes {

    private final OfficeClosureService closures;

    public OpenNowModelAttributes(OfficeClosureService closures) {
        this.closures = closures;
    }

    @ModelAttribute("openNow")
    public String openNow() {
        return closures.openingsJson();
    }
}
