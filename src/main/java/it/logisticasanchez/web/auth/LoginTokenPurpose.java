package it.logisticasanchez.web.auth;

/** Why a one-time link was issued (chapter 08 §2.3 {@code login_token.purpose}). */
public enum LoginTokenPurpose {
    /** Sign in an existing customer user (15 minutes). */
    SIGN_IN,
    /** Verify the e-mail after registration (15 minutes). */
    VERIFY_EMAIL,
    /** Unknown e-mail: a 24-hour link to the registration form, address pre-filled and verified. */
    REGISTER,
    /** Confirm a new e-mail address (CUS-06). */
    CHANGE_EMAIL
}
