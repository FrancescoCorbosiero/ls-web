-- V013: editing and cancelling records (owner decision of 2026-10-25: Q-12 answered; chapter 09
-- CUS-07, ADM-11, ADM-12; ADR 0028). A customer edits or cancels its own PENDING order in the
-- customer area; staff create orders for a customer, edit them (an APPROVED one only as ADMIN)
-- and cancel them; staff create customers from the back office. Cancelling never deletes: the
-- order keeps its row, its snapshots and its history with status CANCELLED (NFR-03 retention,
-- audit log). Every edit also stamps edited_at, so the row version moves on and a decision
-- taken on a page the other side has since changed is refused (optimistic locking).

ALTER TABLE shipment_order DROP CONSTRAINT ck_shipment_order_status;
ALTER TABLE shipment_order ADD CONSTRAINT ck_shipment_order_status
    CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED'));

ALTER TABLE shipment_order
    ADD COLUMN cancelled_at       timestamptz,
    ADD COLUMN cancelled_by_type  varchar(16),
    ADD COLUMN cancelled_by       bigint REFERENCES staff_user (id),
    ADD COLUMN cancel_reason      text,
    ADD COLUMN edited_at          timestamptz,
    ADD COLUMN edited_by_type     varchar(16);

ALTER TABLE shipment_order ADD CONSTRAINT ck_shipment_order_cancelled_is_complete
    CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by_type IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL AND cancelled_by_type IS NULL
            AND cancelled_by IS NULL AND cancel_reason IS NULL));

ALTER TABLE shipment_order ADD CONSTRAINT ck_shipment_order_cancelled_by
    CHECK (cancelled_by_type IS NULL
        OR (cancelled_by_type = 'CUSTOMER' AND cancelled_by IS NULL)
        OR (cancelled_by_type = 'STAFF' AND cancelled_by IS NOT NULL));

ALTER TABLE shipment_order ADD CONSTRAINT ck_shipment_order_edited
    CHECK ((edited_at IS NULL AND edited_by_type IS NULL)
        OR (edited_at IS NOT NULL AND edited_by_type IN ('CUSTOMER', 'STAFF')));

COMMENT ON TABLE shipment_order IS
    'A shipment request (named to avoid the SQL keyword ORDER). States PENDING, APPROVED, DECLINED (chapter 08 §2.4) and CANCELLED (V013, ADR 0028: withdrawn by the customer while pending, or by staff); sender and recipient are snapshots taken at submission.';
COMMENT ON COLUMN shipment_order.cancelled_at IS 'When the order was cancelled (status CANCELLED); the row is never deleted (ADR 0028).';
COMMENT ON COLUMN shipment_order.cancelled_by_type IS 'CUSTOMER (from the customer area, PENDING only) or STAFF (back office; an APPROVED order only by an ADMIN).';
COMMENT ON COLUMN shipment_order.cancelled_by IS 'The staff user who cancelled the order; null when the customer did.';
COMMENT ON COLUMN shipment_order.cancel_reason IS 'Optional reason typed by staff; never shown on public tracking.';
COMMENT ON COLUMN shipment_order.edited_at IS 'Last change of lines, shipment data or recipient after submission (CUS-07, ADM-11); null when never edited.';
COMMENT ON COLUMN shipment_order.edited_by_type IS 'Who made the last change: CUSTOMER or STAFF (the audit log names the user).';

-- A customer created by staff from the back office (ADM-12) is ACTIVE at once, approved by the
-- staff member who created it; its e-mail is verified by the first sign-in link it uses, as for
-- everyone (CUS-01). Self-registered customers still need a verified e-mail before approval (Q-28).
ALTER TABLE customer DROP CONSTRAINT ck_customer_active_is_verified_and_approved;
ALTER TABLE customer ADD CONSTRAINT ck_customer_active_is_verified_and_approved
    CHECK (status <> 'ACTIVE' OR (approved_at IS NOT NULL AND (email_verified_at IS NOT NULL OR source = 'BACKOFFICE')));

COMMENT ON COLUMN customer.email_verified_at IS
    'Set by the first verification or sign-in link used; only a verified customer can be approved (Q-28). A customer created by staff (source BACKOFFICE, ADM-12) is ACTIVE before it is set.';
