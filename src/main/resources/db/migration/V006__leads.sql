-- V006: quote and contact requests of the public site (chapter 08 §2.3 lead; PUB-11,
-- EML-08; the inbox UI is M2, ENH-06). The fields mirror the server-side validation of the
-- new site's form function (chapter 06 §5.5-5.6).

CREATE TABLE lead (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    form                    varchar(12)  NOT NULL,
    status                  varchar(12)  NOT NULL DEFAULT 'NEW',
    name                    varchar(100) NOT NULL,
    company                 varchar(120),
    email                   varchar(254) NOT NULL,
    phone                   varchar(30),
    preferred_contact       varchar(10),
    subject                 varchar(100),
    message                 text,
    service                 varchar(12),
    pickup_date             date,
    pickup_postal_code      varchar(10),
    pickup_city             varchar(100),
    delivery_postal_code    varchar(10),
    delivery_city           varchar(100),
    recipient_type          varchar(8),
    pallet_format           varchar(12),
    quantity                numeric(5,2),
    total_weight_kg         numeric(9,2),
    max_height_cm           numeric(6,1),
    goods_description       varchar(200),
    extras                  text[]       NOT NULL DEFAULT '{}',
    payload                 jsonb        NOT NULL,
    privacy_accepted_at     timestamptz  NOT NULL,
    customer_id             bigint REFERENCES customer (id),
    converted_order_id      bigint REFERENCES shipment_order (id),
    ip_hash                 varchar(64),
    user_agent              varchar(255),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    retain_until            date         NOT NULL,
    CONSTRAINT ck_lead_form CHECK (form IN ('PREVENTIVO', 'CONTATTI')),
    CONSTRAINT ck_lead_status CHECK (status IN ('NEW', 'CONTACTED', 'WON', 'LOST', 'SPAM')),
    CONSTRAINT ck_lead_preferred_contact CHECK (preferred_contact IS NULL OR preferred_contact IN ('email', 'telefono', 'whatsapp')),
    CONSTRAINT ck_lead_service CHECK (service IS NULL OR service IN ('pallet', 'groupage', 'completo', 'magazzino')),
    CONSTRAINT ck_lead_recipient_type CHECK (recipient_type IS NULL OR recipient_type IN ('azienda', 'privato')),
    CONSTRAINT ck_lead_pallet_format CHECK (pallet_format IS NULL OR pallet_format IN ('eur', 'industriale', 'mezzo', 'quarto', 'altro')),
    CONSTRAINT ck_lead_message_length CHECK (message IS NULL OR length(message) <= 5000),
    CONSTRAINT ck_lead_quote_has_phone CHECK (form <> 'PREVENTIVO' OR phone IS NOT NULL)
);

CREATE INDEX ix_lead_status_created ON lead (status, created_at DESC);
CREATE INDEX ix_lead_retain_until ON lead (retain_until);
CREATE INDEX ix_lead_customer ON lead (customer_id) WHERE customer_id IS NOT NULL;

COMMENT ON TABLE lead IS
    'A quote (PREVENTIVO) or contact (CONTATTI) request sent from the public site, stored in the transaction that queues EML-08 (PUB-11). Honeypot submissions are never stored. States: NEW → CONTACTED → WON | LOST; NEW | CONTACTED → SPAM (M2, ENH-06).';
COMMENT ON COLUMN lead.extras IS 'Requested extra services of the quote form (known values only).';
COMMENT ON COLUMN lead.payload IS 'Every validated field with its form name, as received.';
COMMENT ON COLUMN lead.privacy_accepted_at IS 'When the privacy box was ticked (the form requires it).';
COMMENT ON COLUMN lead.customer_id IS 'Set when the e-mail matches a customer, or on conversion (M2).';
COMMENT ON COLUMN lead.ip_hash IS 'HMAC-SHA-256 (hex) of the client IP for abuse control; kept at most 30 days (Q-18).';
COMMENT ON COLUMN lead.retain_until IS 'Retention date from the policy (Q-18, default 24 months); the retention job anonymises the row after it.';
