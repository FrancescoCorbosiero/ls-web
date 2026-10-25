package it.logisticasanchez.web.backoffice;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code staff} (name, role) for every back-office template. */
@ControllerAdvice(basePackageClasses = BackofficeRoutes.class)
public class BackofficeModelAttributes {

    @ModelAttribute("staff")
    public StaffSession staff(@CurrentSecurityContext(expression = "authentication") Authentication authentication) {
        return StaffSession.of(authentication);
    }
}
