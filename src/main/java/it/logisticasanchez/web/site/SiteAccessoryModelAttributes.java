package it.logisticasanchez.web.site;

import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import it.logisticasanchez.web.tracking.TrackingCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * ENH-14 (flag {@code admin.enumerations}, ADR 0043): the two public lists drawn from the
 * accessory services — {@code siteAccessories} for «Servizi accessori» on {@code /servizi/},
 * {@code quoteExtras} for the extras of the quote wizard. Null while the flag is off, and on every
 * other page: the templates then render their ported lists (PUB-01, PUB-06).
 */
@ControllerAdvice(assignableTypes = SitePageController.class)
public class SiteAccessoryModelAttributes {

    private final FeatureFlags flags;
    private final TrackingCatalog catalog;

    public SiteAccessoryModelAttributes(FeatureFlags flags, TrackingCatalog catalog) {
        this.flags = flags;
        this.catalog = catalog;
    }

    @ModelAttribute("siteAccessories")
    public List<TrackingCatalog.SiteService> siteAccessories(HttpServletRequest request) {
        return "/servizi/".equals(request.getRequestURI()) && flags.isEnabled(FlagKey.ADMIN_ENUMERATIONS)
                ? catalog.siteServices()
                : null;
    }

    @ModelAttribute("quoteExtras")
    public List<TrackingCatalog.QuoteExtra> quoteExtras(HttpServletRequest request) {
        return "/preventivo/".equals(request.getRequestURI()) && flags.isEnabled(FlagKey.ADMIN_ENUMERATIONS)
                ? catalog.quoteExtras()
                : null;
    }
}
