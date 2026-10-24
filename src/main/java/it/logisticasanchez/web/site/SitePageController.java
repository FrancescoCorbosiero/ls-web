package it.logisticasanchez.web.site;

import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;

/**
 * Renders the public pages (PUB-01): one handler for every path of {@link SitePages}, mapped
 * programmatically by {@link SitePageMappings}. The template holds the page verbatim; the
 * fragments and the business data come from the layout. Non-indexable pages ({@code /grazie/})
 * also send {@code X-Robots-Tag} (PUB-09).
 */
@Controller
public class SitePageController {

    public static final String NOINDEX = "noindex, nofollow";

    public String page(HttpServletRequest request, HttpServletResponse response) {
        SitePages.Page page = SitePages.byPath(request.getRequestURI())
                .orElseThrow(() -> new ClientErrorException(ErrorCode.NOT_FOUND));
        if (!page.indexable()) {
            response.setHeader("X-Robots-Tag", NOINDEX);
        }
        return page.template();
    }
}
