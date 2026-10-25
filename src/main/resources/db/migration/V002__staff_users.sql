-- V002: staff accounts (chapter 08 §2.3 staff_user; ADM-01, Q-17).
--
-- No account is inserted by any migration (ADM-01): the first ADMIN is created by the
-- create-staff-user command, which prints a one-time set-password link.

CREATE TABLE staff_user (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username        varchar(100) NOT NULL,
    email           varchar(254),
    display_name    varchar(100) NOT NULL,
    password_hash   varchar(255),
    role            varchar(16)  NOT NULL,
    active          boolean      NOT NULL DEFAULT true,
    failed_logins   integer      NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    last_login_at   timestamptz,
    totp_secret     bytea,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_staff_user_username_normalised CHECK (username = lower(btrim(username)) AND username <> ''),
    CONSTRAINT ck_staff_user_email_normalised CHECK (email IS NULL OR email = lower(btrim(email))),
    CONSTRAINT ck_staff_user_role CHECK (role IN ('ADMIN', 'OPERATOR')),
    CONSTRAINT ck_staff_user_failed_logins CHECK (failed_logins >= 0)
);

CREATE UNIQUE INDEX ux_staff_user_username ON staff_user (username);
CREATE UNIQUE INDEX ux_staff_user_email ON staff_user (lower(btrim(email))) WHERE email IS NOT NULL;

CREATE TRIGGER trg_staff_user_updated_at
    BEFORE UPDATE ON staff_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE staff_user IS
    'Back-office accounts (ADM-01). Roles ADMIN and OPERATOR (Q-17). Never seeded by a migration.';
COMMENT ON COLUMN staff_user.username IS 'Login name, stored trimmed and lower-case; unique.';
COMMENT ON COLUMN staff_user.password_hash IS
    'bcrypt or argon2 hash (chapter 10 §4); null until the user sets the password through the one-time link (ADM-01).';
COMMENT ON COLUMN staff_user.failed_logins IS 'Consecutive failed logins, reset on success; drives the lock-out (ADM-01).';
COMMENT ON COLUMN staff_user.locked_until IS 'Lock-out end after repeated failures (ADM-01); null when not locked.';
COMMENT ON COLUMN staff_user.totp_secret IS 'M2 (ENH-09): encrypted TOTP secret; null until two-factor is enabled.';
