-- V014: the customer stays signed in on a device (owner decision of 2026-10-25, chapter 11 PD-10;
-- ADR 0031). After a sign-in link is used, the browser gets a long-lived cookie whose random
-- token restores the customer session when the 60-minute session has ended (idle, 12-hour limit
-- or a restart), until the remembered sign-in expires (ls.security.sessions.customer.remember,
-- default 60 days). Only the SHA-256 of the token is stored, as for login_token. Logout forgets
-- the device; a decline, an e-mail change and the anonymisation forget every device of the login.

CREATE TABLE remembered_sign_in (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_user_id    bigint       NOT NULL REFERENCES customer_user (id),
    token_hash          varchar(64)  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    last_used_at        timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_remembered_sign_in_hash_length CHECK (length(token_hash) = 64),
    CONSTRAINT ck_remembered_sign_in_expires_after_creation CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX ux_remembered_sign_in_hash ON remembered_sign_in (token_hash);
CREATE INDEX ix_remembered_sign_in_customer_user ON remembered_sign_in (customer_user_id);
CREATE INDEX ix_remembered_sign_in_expires_at ON remembered_sign_in (expires_at);

COMMENT ON TABLE remembered_sign_in IS
    'A device on which a customer user stays signed in after using a sign-in link (ADR 0031): the cookie token restores the session until expires_at. Deleted at logout, decline, e-mail change, anonymisation, and by the retention job once expired.';
COMMENT ON COLUMN remembered_sign_in.token_hash IS 'SHA-256 (hex) of a random 256-bit token kept only in the HttpOnly cookie; the raw token is never stored.';
COMMENT ON COLUMN remembered_sign_in.expires_at IS 'The sign-in date plus ls.security.sessions.customer.remember (60 days); not extended by use.';
COMMENT ON COLUMN remembered_sign_in.last_used_at IS 'When the token last restored a session.';
