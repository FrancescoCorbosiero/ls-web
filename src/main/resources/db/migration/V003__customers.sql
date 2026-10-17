-- V003: customers, their login identities, one-time links and the data a declined
-- customer sends when registering again (chapter 08 §2.3 customer, customer_user,
-- login_token; plan §4.4 customer_pending_registration; Q-09, Q-10, Q-11, Q-28, Q-36).

CREATE TABLE customer (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email               varchar(254) NOT NULL,
    company_name        varchar(200) NOT NULL,
    address_line        varchar(200) NOT NULL,
    city                varchar(100) NOT NULL,
    province            varchar(60)  NOT NULL,
    province_code       char(2),
    postal_code         varchar(10)  NOT NULL,
    phone               varchar(30)  NOT NULL,
    vat_number          varchar(16),
    tax_code            varchar(16),
    status              varchar(24)  NOT NULL,
    email_verified_at   timestamptz,
    registered_at       timestamptz  NOT NULL DEFAULT now(),
    approved_at         timestamptz,
    approved_by         bigint REFERENCES staff_user (id),
    declined_at         timestamptz,
    declined_by         bigint REFERENCES staff_user (id),
    decline_reason      text,
    source              varchar(16)  NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             integer      NOT NULL DEFAULT 0,
    CONSTRAINT ck_customer_email_normalised CHECK (email = lower(btrim(email)) AND email !~ '\s' AND email <> ''),
    CONSTRAINT ck_customer_postal_code CHECK (postal_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_customer_status CHECK (status IN ('PENDING_VERIFICATION', 'PENDING_APPROVAL', 'ACTIVE', 'DECLINED')),
    CONSTRAINT ck_customer_source CHECK (source IN ('CUSTOMER_AREA', 'BACKOFFICE')),
    CONSTRAINT ck_customer_active_is_verified_and_approved
        CHECK (status <> 'ACTIVE' OR (email_verified_at IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_customer_declined_has_time CHECK (status <> 'DECLINED' OR declined_at IS NOT NULL),
    CONSTRAINT ck_customer_pending_approval_is_verified
        CHECK (status <> 'PENDING_APPROVAL' OR email_verified_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_customer_email ON customer (lower(btrim(email)));
CREATE INDEX ix_customer_status ON customer (status);
CREATE INDEX ix_customer_created_at ON customer (created_at);

CREATE TRIGGER trg_customer_updated_at
    BEFORE UPDATE ON customer
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE customer IS
    'A registered sender (company or private person, Q-10). States of chapter 08 §2.4: PENDING_VERIFICATION, PENDING_APPROVAL, ACTIVE, DECLINED.';
COMMENT ON COLUMN customer.email IS 'The M1 login identity, trimmed and lower-case; unique (Q-11).';
COMMENT ON COLUMN customer.company_name IS '"Ragione Sociale/Nominativo" of the legacy portal.';
COMMENT ON COLUMN customer.province IS 'Province as typed and trimmed; province_code is the two-letter code when it matches one.';
COMMENT ON COLUMN customer.postal_code IS 'Italian CAP, exactly five digits (CUS-02).';
COMMENT ON COLUMN customer.phone IS 'Required at registration (Q-36), E.164 when possible.';
COMMENT ON COLUMN customer.email_verified_at IS 'Set by the first verification or sign-in link used; only a verified customer can be approved (Q-28).';
COMMENT ON COLUMN customer.registered_at IS 'When the registration form was sent.';
COMMENT ON COLUMN customer.source IS 'Who created the record: CUSTOMER_AREA (registration) or BACKOFFICE.';
COMMENT ON COLUMN customer.version IS 'Optimistic locking.';

-- Data a DECLINED customer sends when registering again (Q-09): stored apart and applied
-- to the customer row only when the verification link is used, then deleted.
CREATE TABLE customer_pending_registration (
    customer_id     bigint PRIMARY KEY REFERENCES customer (id),
    company_name    varchar(200) NOT NULL,
    address_line    varchar(200) NOT NULL,
    city            varchar(100) NOT NULL,
    province        varchar(60)  NOT NULL,
    province_code   char(2),
    postal_code     varchar(10)  NOT NULL,
    phone           varchar(30)  NOT NULL,
    vat_number      varchar(16),
    tax_code        varchar(16),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_pending_registration_postal_code CHECK (postal_code ~ '^[0-9]{5}$')
);

COMMENT ON TABLE customer_pending_registration IS
    'Re-registration data of a declined customer, kept apart until the verification link is used (Q-09, CUS-02); one row per customer.';

CREATE TABLE customer_user (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     bigint       NOT NULL REFERENCES customer (id),
    email           varchar(254) NOT NULL,
    role            varchar(16)  NOT NULL DEFAULT 'OWNER',
    last_login_at   timestamptz,
    disabled_at     timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_user_email_normalised CHECK (email = lower(btrim(email)) AND email !~ '\s' AND email <> ''),
    CONSTRAINT ck_customer_user_role CHECK (role IN ('OWNER', 'MEMBER'))
);

CREATE UNIQUE INDEX ux_customer_user_email ON customer_user (lower(btrim(email)));
CREATE UNIQUE INDEX ux_customer_user_one_owner ON customer_user (customer_id) WHERE role = 'OWNER';
CREATE INDEX ix_customer_user_customer ON customer_user (customer_id);

COMMENT ON TABLE customer_user IS
    'Login identity of a customer (magic link, CUS-01). M1: exactly one OWNER per customer with the customer''s e-mail; MEMBER is reserved for later (Q-11).';
COMMENT ON COLUMN customer_user.disabled_at IS 'Set when the login is disabled; a disabled user cannot ask for a link.';

CREATE TABLE login_token (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_user_id    bigint REFERENCES customer_user (id),
    email_hash          varchar(64),
    purpose             varchar(16)  NOT NULL,
    token_hash          varchar(64)  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    used_at             timestamptz,
    requested_ip_hash   varchar(64),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_login_token_purpose CHECK (purpose IN ('SIGN_IN', 'VERIFY_EMAIL', 'REGISTER', 'CHANGE_EMAIL')),
    CONSTRAINT ck_login_token_subject CHECK (
        (purpose = 'REGISTER' AND customer_user_id IS NULL AND email_hash IS NOT NULL)
        OR (purpose <> 'REGISTER' AND customer_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_login_token_hash_length CHECK (length(token_hash) = 64),
    CONSTRAINT ck_login_token_expires_after_creation CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX ux_login_token_hash ON login_token (token_hash);
CREATE INDEX ix_login_token_customer_user ON login_token (customer_user_id);
CREATE INDEX ix_login_token_expires_at ON login_token (expires_at);

COMMENT ON TABLE login_token IS
    'One-time links of the customer area (CUS-01, chapter 10 §2): sign-in, e-mail verification, registration of an unknown e-mail, e-mail change. Single use, consumed only by the CSRF-protected POST.';
COMMENT ON COLUMN login_token.customer_user_id IS 'Null only for REGISTER (unknown e-mail): the row then carries email_hash instead.';
COMMENT ON COLUMN login_token.email_hash IS 'REGISTER only: HMAC-SHA-256 (hex) of the normalised e-mail, keyed from the secret store.';
COMMENT ON COLUMN login_token.token_hash IS 'SHA-256 (hex) of a random token of at least 128 bits; the raw token is never stored.';
COMMENT ON COLUMN login_token.expires_at IS '15 minutes after creation; REGISTER links 24 hours (chapter 10 §2).';
COMMENT ON COLUMN login_token.requested_ip_hash IS 'HMAC-SHA-256 (hex) of the requesting IP for rate limits; kept at most 30 days (Q-18).';
