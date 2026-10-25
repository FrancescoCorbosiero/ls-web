package it.logisticasanchez.web.auth;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;

/**
 * The back-office home for a staff session (ADR 0029): {@code /gestione/} is both the login page
 * and the home, and only the back-office module knows what the home shows (the work queue, the
 * latest orders). It implements this extension point; without it the minimal section cards of
 * {@code auth/staff-home} are shown.
 */
public interface StaffHomePage {

    /** Fills the model for the signed-in staff member and returns the view name. */
    String render(Authentication authentication, Model model);
}
