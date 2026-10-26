-- V019: the customer's own data changes (chapter 09 ENH-16, flag customers.profileEdit; ADR 0038).
-- An ACTIVE customer edits its data from the profile: the address and the phone change at once;
-- the company name, the VAT number and the tax code (who the customer is for the invoices) are
-- a request the office approves or declines. One pending request per customer: a new save
-- replaces its values. The table belongs to the customers module; anonymisation (NFR-03)
-- deletes the customer's requests. The outcome e-mail to the customer is EML-12.

CREATE TABLE customer_change_request (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     bigint        NOT NULL REFERENCES customer (id),
    status          varchar(10)   NOT NULL DEFAULT 'PENDING',
    company_name    varchar(200)  NOT NULL,
    vat_number      varchar(16),
    tax_code        varchar(16),
    requested_at    timestamptz   NOT NULL,
    closed_at       timestamptz,
    decided_by      bigint        REFERENCES staff_user (id),
    decline_reason  varchar(500),
    CONSTRAINT ck_customer_change_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    CONSTRAINT ck_customer_change_request_closed CHECK ((status = 'PENDING') = (closed_at IS NULL)),
    CONSTRAINT ck_customer_change_request_company_name CHECK (btrim(company_name) <> '')
);

CREATE UNIQUE INDEX ux_customer_change_request_pending
    ON customer_change_request (customer_id) WHERE status = 'PENDING';
CREATE INDEX ix_customer_change_request_customer ON customer_change_request (customer_id);

COMMENT ON TABLE customer_change_request IS
    'A change of company name, VAT number or tax code asked by the customer from its profile (ENH-16, ADR 0038): the values it asked for, PENDING until the office approves (APPROVED, copied to the customer) or declines it (DECLINED), or the customer takes it back (WITHDRAWN).';
COMMENT ON COLUMN customer_change_request.decided_by IS 'The staff user who approved or declined; null while pending and for a withdrawn request.';
