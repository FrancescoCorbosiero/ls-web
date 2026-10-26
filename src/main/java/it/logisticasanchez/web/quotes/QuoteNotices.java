package it.logisticasanchez.web.quotes;

import it.logisticasanchez.web.auth.CustomerPrincipal;
import it.logisticasanchez.web.auth.CustomerSessionService;
import it.logisticasanchez.web.orders.OrderRoutes;
import it.logisticasanchez.web.platform.flags.FeatureFlags;
import it.logisticasanchez.web.platform.flags.FlagKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ENH-20 (ADR 0037): «I miei ordini» — where a customer lands after signing in — lists the quotes
 * the customer can still order ({@code openQuotes}), so the link of the e-mail is not the only way
 * back to them. An interceptor of this module, so that the orders module knows nothing of quotes.
 */
@Component
public class QuoteNotices implements HandlerInterceptor, WebMvcConfigurer {

    private final QuoteService quotes;
    private final FeatureFlags flags;

    public QuoteNotices(QuoteService quotes, FeatureFlags flags) {
        this.quotes = quotes;
        this.flags = flags;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(OrderRoutes.LIST);
    }

    @Override
    public void postHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView view) {
        if (view == null || view.getViewName() == null || view.getViewName().startsWith("redirect:")) {
            return;
        }
        if (!flags.isEnabled(FlagKey.QUOTES_SAVED)) {
            return;
        }
        CustomerPrincipal principal = CustomerSessionService.current(
                SecurityContextHolder.getContext().getAuthentication());
        if (principal != null) {
            view.addObject("openQuotes", quotes.openFor(principal.customerId()));
        }
    }
}
