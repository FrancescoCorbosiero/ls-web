-- V010: one-time set-password links of the back office (ADM-01, chapter 08 §3.9, ADR 0003)
-- and the outbox changes they need.
--
-- A staff account is created without a password (create-staff-user command or an ADMIN
-- through the service); the user sets it through a link valid 24 hours and usable once.
-- The row stores only the SHA-256 of the random token, like login_token does for customers.

CREATE TABLE staff_setup_token (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    staff_user_id   bigint       NOT NULL REFERENCES staff_user (id),
    token_hash      varchar(64)  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_staff_setup_token_hash_length CHECK (length(token_hash) = 64),
    CONSTRAINT ck_staff_setup_token_expires_after_creation CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX ux_staff_setup_token_hash ON staff_setup_token (token_hash);
CREATE INDEX ix_staff_setup_token_staff_user ON staff_setup_token (staff_user_id);

COMMENT ON TABLE staff_setup_token IS
    'One-time set-password links of staff accounts (ADM-01): SHA-256 of the token, 24 hours, single use. Consumed only by the POST of the set-password page.';
COMMENT ON COLUMN staff_setup_token.token_hash IS 'SHA-256 (hex) of a random token of at least 128 bits; the raw token is never stored.';
COMMENT ON COLUMN staff_setup_token.used_at IS 'When the password was set through this link; null while unused.';

-- The outbox also carries the staff set-password e-mail (kind EML-S1, outside the customer
-- catalogue EML-01…09) and its encrypted link token, which references no login_token row.
ALTER TABLE email_outbox DROP CONSTRAINT ck_email_outbox_kind;
ALTER TABLE email_outbox ADD CONSTRAINT ck_email_outbox_kind CHECK (kind ~ '^EML-(0[1-9]|S[1-9])$');
ALTER TABLE email_outbox DROP CONSTRAINT ck_email_outbox_token_pair;

COMMENT ON COLUMN email_outbox.kind IS
    'The catalogue entry of chapter 09 (EML-01 … EML-09) or a staff e-mail (EML-S1: set-password link).';
COMMENT ON COLUMN email_outbox.token_ciphertext IS
    'The raw one-time token (customer login_token or staff_setup_token), encrypted with a key from the secret store; the worker decrypts it to build the link and wipes it after delivery. Never logged or shown.';
