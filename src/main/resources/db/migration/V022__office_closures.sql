-- V022: the days the office is closed outside its weekly hours (chapter 09 ENH-18, flag
-- site.openNow; ADR 0042): holidays, August, an unexpected closure. With the weekly hours of the
-- configuration (PUB-02) they make «Aperto ora / Chiuso ora» next to the hours of the public site.

CREATE TABLE office_closure (
    day         date          PRIMARY KEY,
    label       varchar(100),
    created_by  bigint        NOT NULL REFERENCES staff_user (id),
    created_at  timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE office_closure IS
    'Days the office is closed although the weekly hours say open (ENH-18, ADR 0042); a day in Europe/Rome.';
COMMENT ON COLUMN office_closure.label IS 'Why, for the back office (for example «Natale»); never shown on the site.';
