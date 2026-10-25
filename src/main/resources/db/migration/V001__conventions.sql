-- V001: conventions shared by every table (ADR 0020).
--
-- PostgreSQL 16. No extension is needed: gen_random_uuid() and the identity columns are
-- built in. Owner decision Q-55 (2026-10-17): the new app starts with an empty database,
-- so no table carries legacy ids, raw values or import bookkeeping.
--
-- Conventions:
--   * primary keys: bigint generated always as identity, unless the table is a seeded
--     enumeration whose ids are the legacy ids (tracking_state, accessory_service);
--   * every timestamp is timestamptz, stored in UTC, shown in Europe/Rome by the app;
--   * money is numeric(10,2) in EUR with an explicit VAT flag;
--   * state columns are varchar with a CHECK constraint named ck_<table>_<column>;
--   * created_at / updated_at default to now(); updated_at is kept by the trigger below.

-- Keeps updated_at current on every UPDATE of a table that carries the column, whichever
-- client writes the row (the app through JPA, psql, a maintenance job).
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION set_updated_at() IS
    'Row trigger: sets updated_at = now() before every UPDATE (ADR 0020).';
