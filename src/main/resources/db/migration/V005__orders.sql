-- V005: orders, their lines, the internal note and the tracking history (chapter 08 §2.3
-- shipment_order, order_line, order_internal_note, tracking_event; 12b §5.4; plan §8.1
-- Q-05, Q-06, Q-13, Q-24, Q-26, Q-35). Strict rules: no legacy row will ever exist (Q-55).

CREATE TABLE shipment_order (
    id                          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id                 bigint        NOT NULL REFERENCES customer (id),
    status                      varchar(16)   NOT NULL DEFAULT 'PENDING',
    temp_code                   varchar(64)   NOT NULL,
    code                        varchar(64),
    transport_document_code     varchar(100)  NOT NULL,
    package_count               integer,
    accessory_service_id        integer REFERENCES accessory_service (id),
    customer_notes              text,
    amount                      numeric(10,2),
    amount_vat_included         boolean,
    total_weight_kg             numeric(12,2) NOT NULL,
    total_volume_m3             numeric(10,2) NOT NULL,
    tracking_state_id           smallint      NOT NULL DEFAULT 0 REFERENCES tracking_state (id),
    tracking_updated_at         timestamptz,
    sender_name                 varchar(200)  NOT NULL,
    sender_address_line         varchar(200)  NOT NULL,
    sender_city                 varchar(100)  NOT NULL,
    sender_province             varchar(60)   NOT NULL,
    sender_postal_code          varchar(10)   NOT NULL,
    sender_email                varchar(254)  NOT NULL,
    sender_phone                varchar(30),
    recipient_name              varchar(200)  NOT NULL,
    recipient_address_line      varchar(200)  NOT NULL,
    recipient_city              varchar(100)  NOT NULL,
    recipient_province          varchar(60)   NOT NULL,
    recipient_postal_code       varchar(10)   NOT NULL,
    recipient_phone             varchar(30),
    recipient_email             varchar(254),
    submitted_at                timestamptz   NOT NULL DEFAULT now(),
    approved_at                 timestamptz,
    approved_by                 bigint REFERENCES staff_user (id),
    declined_at                 timestamptz,
    declined_by                 bigint REFERENCES staff_user (id),
    decline_reason              text,
    idempotency_key             uuid,
    source                      varchar(16)   NOT NULL,
    created_at                  timestamptz   NOT NULL DEFAULT now(),
    updated_at                  timestamptz   NOT NULL DEFAULT now(),
    version                     integer       NOT NULL DEFAULT 0,
    CONSTRAINT ck_shipment_order_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED')),
    CONSTRAINT ck_shipment_order_source CHECK (source IN ('CUSTOMER_AREA', 'BACKOFFICE')),
    CONSTRAINT ck_shipment_order_temp_code CHECK (temp_code = btrim(temp_code) AND temp_code <> ''),
    CONSTRAINT ck_shipment_order_code CHECK (code IS NULL OR (code = btrim(code) AND code <> '')),
    CONSTRAINT ck_shipment_order_transport_document_code CHECK (btrim(transport_document_code) <> ''),
    CONSTRAINT ck_shipment_order_package_count CHECK (package_count IS NULL OR package_count >= 0),
    CONSTRAINT ck_shipment_order_amount CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_shipment_order_totals CHECK (total_weight_kg >= 0 AND total_volume_m3 >= 0),
    CONSTRAINT ck_shipment_order_approved_is_complete
        CHECK (status <> 'APPROVED' OR (code IS NOT NULL AND amount IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT ck_shipment_order_declined_has_time CHECK (status <> 'DECLINED' OR declined_at IS NOT NULL),
    CONSTRAINT ck_shipment_order_pending_has_no_outcome
        CHECK (status <> 'PENDING' OR (approved_at IS NULL AND declined_at IS NULL)),
    CONSTRAINT ck_shipment_order_sender_postal_code CHECK (sender_postal_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_shipment_order_recipient_postal_code CHECK (recipient_postal_code ~ '^[0-9]{5}$')
);

CREATE UNIQUE INDEX ux_shipment_order_temp_code ON shipment_order (lower(btrim(temp_code)));
CREATE UNIQUE INDEX ux_shipment_order_code ON shipment_order (lower(btrim(code))) WHERE code IS NOT NULL;
CREATE UNIQUE INDEX ux_shipment_order_idempotency_key ON shipment_order (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_shipment_order_customer ON shipment_order (customer_id, created_at DESC);
CREATE INDEX ix_shipment_order_status ON shipment_order (status);
CREATE INDEX ix_shipment_order_tracking_state ON shipment_order (tracking_state_id);
CREATE INDEX ix_shipment_order_created_at ON shipment_order (created_at);

CREATE TRIGGER trg_shipment_order_updated_at
    BEFORE UPDATE ON shipment_order
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE shipment_order IS
    'A shipment request (named to avoid the SQL keyword ORDER). States PENDING, APPROVED, DECLINED (chapter 08 §2.4); sender and recipient are snapshots taken at submission.';
COMMENT ON COLUMN shipment_order.temp_code IS 'Temporary code given at submission (TEMP- plus random base-32 characters); unique ignoring case.';
COMMENT ON COLUMN shipment_order.code IS 'The definitive shipment code typed by staff at approval, kept as typed; unique trimmed and ignoring case (Q-06).';
COMMENT ON COLUMN shipment_order.transport_document_code IS 'Transport document (DDT) code, required for every order (Q-24).';
COMMENT ON COLUMN shipment_order.package_count IS '"Numero Colli", optional count (Q-24).';
COMMENT ON COLUMN shipment_order.accessory_service_id IS 'Null = no accessory service (the is_none row is never referenced).';
COMMENT ON COLUMN shipment_order.customer_notes IS 'The customer''s "Note" typed in the wizard (Q-26); staff notes live in order_internal_note.';
COMMENT ON COLUMN shipment_order.amount IS '"Importo Totale (per cliente)" in EUR, VAT excluded by default (Q-05); set at approval.';
COMMENT ON COLUMN shipment_order.amount_vat_included IS 'Whether amount includes VAT (Q-05 seam); set together with amount.';
COMMENT ON COLUMN shipment_order.total_weight_kg IS 'Sum of order_line.line_weight_kg.';
COMMENT ON COLUMN shipment_order.total_volume_m3 IS 'Sum of order_line.line_volume_m3.';
COMMENT ON COLUMN shipment_order.tracking_state_id IS 'Current tracking state (0 = NON_GESTITO); equals the latest tracking_event once one exists.';
COMMENT ON COLUMN shipment_order.sender_email IS 'Snapshot of the customer at submission (Q-13): the pick-up address is the registered address.';
COMMENT ON COLUMN shipment_order.recipient_email IS 'New in the customer area (CUS-04); optional.';
COMMENT ON COLUMN shipment_order.idempotency_key IS 'One per wizard session: a repeated submission creates no second order (CUS-04).';
COMMENT ON COLUMN shipment_order.source IS 'Who created the order: CUSTOMER_AREA (wizard) or BACKOFFICE.';

CREATE TABLE order_line (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        bigint        NOT NULL REFERENCES shipment_order (id),
    line_no         smallint      NOT NULL,
    kind            varchar(8)    NOT NULL,
    height_cm       integer       NOT NULL,
    length_cm       integer       NOT NULL,
    width_cm        integer       NOT NULL,
    weight_kg       numeric(9,2)  NOT NULL,
    quantity        integer       NOT NULL,
    line_weight_kg  numeric(12,2) NOT NULL,
    line_volume_m3  numeric(10,2) NOT NULL,
    CONSTRAINT uq_order_line_no UNIQUE (order_id, line_no),
    CONSTRAINT ck_order_line_no CHECK (line_no >= 1),
    CONSTRAINT ck_order_line_kind CHECK (kind IN ('GENERIC', 'EPAL')),
    CONSTRAINT ck_order_line_dimensions CHECK (height_cm >= 1 AND length_cm >= 1 AND width_cm >= 1),
    CONSTRAINT ck_order_line_weight CHECK (weight_kg > 0),
    CONSTRAINT ck_order_line_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_order_line_totals CHECK (line_weight_kg >= 0 AND line_volume_m3 >= 0)
);

COMMENT ON TABLE order_line IS 'One line of the wizard: a pallet type with dimensions, unit weight and quantity (CUS-04, Q-35).';
COMMENT ON COLUMN order_line.kind IS 'GENERIC ("Pallet Generico/Colli") or EPAL ("Epal").';
COMMENT ON COLUMN order_line.weight_kg IS 'Weight per unit.';
COMMENT ON COLUMN order_line.line_weight_kg IS 'weight_kg × quantity.';
COMMENT ON COLUMN order_line.line_volume_m3 IS 'round_half_up(height × length × width / 1 000 000 × quantity, 2): the legacy per-line formula.';

CREATE TABLE order_internal_note (
    order_id    bigint PRIMARY KEY REFERENCES shipment_order (id),
    body        text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  bigint      NOT NULL REFERENCES staff_user (id),
    CONSTRAINT ck_order_internal_note_body CHECK (length(body) <= 5000)
);

COMMENT ON TABLE order_internal_note IS
    'The staff note of an order (ADM-07, Q-26): one per order, never shown to the customer; every edit writes the audit log with the before and after text in audit_log_detail.';

CREATE TABLE tracking_event (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        bigint      NOT NULL REFERENCES shipment_order (id),
    state_id        smallint    NOT NULL REFERENCES tracking_state (id),
    occurred_at     timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    source          varchar(16) NOT NULL,
    staff_user_id   bigint REFERENCES staff_user (id),
    location        varchar(120),
    public_note     varchar(40),
    CONSTRAINT ck_tracking_event_source CHECK (source IN ('APPROVAL', 'STAFF', 'BULK'))
);

CREATE INDEX ix_tracking_event_order_time ON tracking_event (order_id, occurred_at DESC, id DESC);

COMMENT ON TABLE tracking_event IS
    'Append-only tracking history (ADM-06): every state change is an event; a correction is a new event. The order''s tracking_state_id and tracking_updated_at change in the same transaction.';
COMMENT ON COLUMN tracking_event.source IS 'APPROVAL (the PRESA event written at approval), STAFF (back office), BULK (ENH-15).';
COMMENT ON COLUMN tracking_event.location IS 'M2 timeline (ENH-05): a city and province or a hub name; never personal data (TRK-03).';
COMMENT ON COLUMN tracking_event.public_note IS 'M2 timeline (ENH-05): a key from a fixed list of phrases, no free text (TRK-03).';
