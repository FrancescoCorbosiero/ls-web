-- V020: two-factor sign-in for staff (chapter 09 ENH-09, flag security.adminTotp; ADR 0039).
-- With the flag on, a staff login asks, after the password, the 6-digit code of an authenticator
-- app (RFC 6238 TOTP). staff_user.totp_secret (V002) holds the encrypted secret once the user has
-- confirmed it with a first code; totp_enabled_at says when; totp_last_step is the 30-second step
-- of the last code accepted, so the same code is never accepted twice. M1 never writes a secret,
-- so the check below holds for every existing row.

ALTER TABLE staff_user ADD COLUMN totp_enabled_at timestamptz;
ALTER TABLE staff_user ADD COLUMN totp_last_step bigint;
ALTER TABLE staff_user ADD CONSTRAINT ck_staff_user_totp_complete
    CHECK ((totp_secret IS NULL) = (totp_enabled_at IS NULL));

COMMENT ON COLUMN staff_user.totp_secret IS
    'ENH-09: AES-256-GCM ciphertext (12-byte IV first) of the 20-byte TOTP secret, key derived from LS_TOKEN_ENCRYPTION_KEY; null until two-factor is set up or after an ADMIN reset.';
COMMENT ON COLUMN staff_user.totp_enabled_at IS 'ENH-09: when the user confirmed the TOTP secret with a first code.';
COMMENT ON COLUMN staff_user.totp_last_step IS 'ENH-09: the 30-second step (Unix time / 30) of the last accepted code; a code of that step or an earlier one is refused.';
