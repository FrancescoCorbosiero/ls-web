-- V017: the e-mail «Aggiornamento spedizione» (ENH-05, flag tracking.notifyCustomer; ADR 0034).
-- With the flag on the customer gets one e-mail at every change of the tracking state to
-- TRANSITO, TERMINAL_DEST or ASSEGNATO (PRESA is told by the approval e-mail EML-05, CONSEGNATO
-- by EML-09). It is the outbox kind EML-10, next to the catalogue entries EML-01…EML-09.

ALTER TABLE email_outbox DROP CONSTRAINT ck_email_outbox_kind;
ALTER TABLE email_outbox ADD CONSTRAINT ck_email_outbox_kind CHECK (kind ~ '^EML-(0[1-9]|10|S[1-9])$');

COMMENT ON COLUMN email_outbox.kind IS
    'The catalogue entry of chapter 09 (EML-01 … EML-09), the tracking update of ENH-05 (EML-10) or a staff e-mail (EML-S1: set-password link).';
