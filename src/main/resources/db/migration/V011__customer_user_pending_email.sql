-- V011: the e-mail a customer asked to switch to (CUS-06). Set when the change is requested
-- from the signed-in area together with a CHANGE_EMAIL login_token; applied to customer_user
-- and customer when the new address confirms the link, then cleared. Never a login identity
-- until applied.

ALTER TABLE customer_user ADD COLUMN pending_email varchar(254);

ALTER TABLE customer_user ADD CONSTRAINT ck_customer_user_pending_email_normalised
    CHECK (pending_email IS NULL OR (pending_email = lower(btrim(pending_email)) AND pending_email !~ '\s' AND pending_email <> ''));

COMMENT ON COLUMN customer_user.pending_email IS
    'New login e-mail awaiting confirmation by its CHANGE_EMAIL link (CUS-06); trimmed and lower-case; cleared when applied.';
