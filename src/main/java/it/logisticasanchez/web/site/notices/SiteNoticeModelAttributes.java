package it.logisticasanchez.web.site.notices;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code siteNotice} for the public pages of the site module (ENH-17): the notice in force, or null. */
@ControllerAdvice(basePackages = "it.logisticasanchez.web.site")
public class SiteNoticeModelAttributes {

    private final SiteNoticeService notices;

    public SiteNoticeModelAttributes(SiteNoticeService notices) {
        this.notices = notices;
    }

    @ModelAttribute("siteNotice")
    public SiteNoticeView siteNotice() {
        return notices.current();
    }
}
