package it.logisticasanchez.web.tracking;

import it.logisticasanchez.web.platform.config.BusinessProperties;
import it.logisticasanchez.web.platform.security.SecurityRoutes;
import it.logisticasanchez.web.platform.web.error.ClientErrorException;
import it.logisticasanchez.web.platform.web.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Every other legacy path under {@code /mono/**} (API-03, chapter 02 §10.3, ADR 0011): the
 * retired endpoints answer {@code 410 Gone} with the JSON error schema and never perform
 * their old action; the {@code GET} links of e-mails already sent ({@code /mono/email-confirm},
 * {@code /mono/report}, {@code /mono/download-report}, the old success pages) get a small
 * Italian page (status 410) pointing to {@code /area-clienti/}, identical for every
 * parameter — nothing is confirmed, no report is served; the logo the old e-mails embed is
 * served from the site assets. Served on every host the app answers (apex and {@code app.}).
 */
@Controller
public class MonoGoneController {

    public static final String LOGO_PATH = "/mono/img/logistica-sanchez-logo.jpg";
    static final Set<String> OLD_LINK_PATHS =
            Set.of("/mono/email-confirm", "/mono/report", "/mono/download-report", "/mono/request-success.html");
    private static final Resource LOGO = new ClassPathResource("static/img/brand/logo-square.png");

    private final BusinessProperties business;

    public MonoGoneController(BusinessProperties business) {
        this.business = business;
    }

    @RequestMapping("/mono/**")
    public Object gone(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (read && LOGO_PATH.equals(path)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(
                            CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                    .body(LOGO);
        }
        if (read && isOldLink(path)) {
            response.setHeader("X-Robots-Tag", "noindex, nofollow");
            ModelAndView page = new ModelAndView("compat/gone", HttpStatus.GONE);
            page.addObject("customerArea", SecurityRoutes.CUSTOMER_AREA);
            return page;
        }
        throw new ClientErrorException(
                ErrorCode.GONE,
                "Servizio non più disponibile: usa " + business.siteUrl() + SecurityRoutes.CUSTOMER_AREA);
    }

    /** The links the legacy backend wrote into its e-mails and the view pages they led to. */
    static boolean isOldLink(String path) {
        return OLD_LINK_PATHS.contains(path) || (path.startsWith("/mono/mono/") && path.endsWith(".html"));
    }
}
