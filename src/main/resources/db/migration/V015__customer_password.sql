-- V015: customers may also sign in with e-mail and password (owner decision of 2026-10-25,
-- ADR 0032): «se questo maledetto link non è comodo mettiamo una semplice basic auth». The
-- e-mailed link stays for the first access, the registration and a forgotten password. Only a
-- bcrypt hash is stored ({bcrypt} prefix of the DelegatingPasswordEncoder, as staff_user); the
-- anonymisation clears it.

ALTER TABLE customer_user
    ADD COLUMN password_hash        varchar(255),
    ADD COLUMN password_changed_at  timestamptz;

ALTER TABLE customer_user ADD CONSTRAINT ck_customer_user_password_complete
    CHECK ((password_hash IS NULL) = (password_changed_at IS NULL));

COMMENT ON COLUMN customer_user.password_hash IS 'Hash of the customer''s password ({bcrypt}…, ADR 0032); null while the customer signs in only by e-mailed link.';
COMMENT ON COLUMN customer_user.password_changed_at IS 'When the password was last set; null without a password.';
