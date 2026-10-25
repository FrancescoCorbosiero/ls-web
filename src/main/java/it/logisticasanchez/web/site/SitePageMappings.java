package it.logisticasanchez.web.site;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers {@code GET <path>} → {@link SitePageController#page} for every page of
 * {@link SitePages}, so the inventory stays the one source of the public URLs (an annotation
 * would repeat the 25 paths). The mappings are ordinary handler methods: the route × role
 * matrix test sees them like any other.
 */
@Component
public class SitePageMappings {

    public SitePageMappings(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping,
            SitePageController controller)
            throws NoSuchMethodException {
        Method handler =
                SitePageController.class.getMethod("page", HttpServletRequest.class, HttpServletResponse.class);
        for (SitePages.Page page : SitePages.all()) {
            RequestMappingInfo info = RequestMappingInfo.paths(page.path())
                    .methods(RequestMethod.GET)
                    .options(mapping.getBuilderConfiguration())
                    .build();
            mapping.registerMapping(info, controller, handler);
        }
    }
}
