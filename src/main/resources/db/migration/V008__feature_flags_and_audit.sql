-- V008: feature flags (chapter 10 §2 "Feature flags"; ADR 0025) and the audit log
-- (chapter 08 §2.3 audit_log, audit_log_detail; NFR-02; ADR 0020).

CREATE TABLE feature_flag (
    key             varchar(60) PRIMARY KEY,
    enabled         boolean     NOT NULL DEFAULT false,
    requirement_id  varchar(8)  NOT NULL,
    updated_by      bigint REFERENCES staff_user (id),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_feature_flag_key CHECK (key ~ '^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)*$'),
    CONSTRAINT ck_feature_flag_requirement_id CHECK (requirement_id ~ '^ENH-[0-9]{2}$')
);

CREATE TRIGGER trg_feature_flag_updated_at
    BEFORE UPDATE ON feature_flag
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE feature_flag IS
    'M2 feature flags, one row per key of chapter 09 §M2, all off by default; ADMIN users toggle them in /gestione/impostazioni/. A disabled flag behaves exactly as M1 (ADR 0025).';
COMMENT ON COLUMN feature_flag.requirement_id IS 'The ENH requirement the flag belongs to (chapter 09 §M2).';
COMMENT ON COLUMN feature_flag.updated_by IS 'The staff user who last toggled the flag; null for the seeded value.';

CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_type  varchar(10) NOT NULL,
    actor_id    bigint,
    action      varchar(60) NOT NULL,
    entity_type varchar(40),
    entity_id   bigint,
    entity_ref  varchar(100),
    before      jsonb,
    after       jsonb,
    ip_hash     varchar(64),
    user_agent  varchar(255),
    request_id  varchar(64),
    CONSTRAINT ck_audit_log_actor_type CHECK (actor_type IN ('STAFF', 'CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_audit_log_action CHECK (action ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$')
);

CREATE INDEX ix_audit_log_occurred_at ON audit_log (occurred_at);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_log_actor ON audit_log (actor_type, actor_id);

COMMENT ON TABLE audit_log IS
    'Append-only record of every staff, customer and system action (NFR-02). UPDATE is refused; DELETE only by the retention job, which sets ls.audit_maintenance = ''on'' in its transaction (ADR 0020). Holds ids, never personal data: those go to audit_log_detail.';
COMMENT ON COLUMN audit_log.action IS 'Dotted action name, e.g. customer.approve, order.approve, order.tracking.set, order.note.update, report.send, staff.login.failed, flag.toggle.';
COMMENT ON COLUMN audit_log.entity_ref IS 'An order code or an internal id, never an e-mail.';
COMMENT ON COLUMN audit_log.before IS 'Changed field names with non-personal values; personal fields appear by name only.';
COMMENT ON COLUMN audit_log.after IS 'As before, after the change.';
COMMENT ON COLUMN audit_log.ip_hash IS 'HMAC-SHA-256 (hex) of the client IP; kept at most 30 days (Q-18).';

CREATE TABLE audit_log_detail (
    audit_log_id    bigint PRIMARY KEY REFERENCES audit_log (id),
    before          jsonb,
    after           jsonb
);

COMMENT ON TABLE audit_log_detail IS
    'Personal values of an audited change (note text, address …), kept apart so the erasure tool (NFR-03) and the retention job (Q-18) can delete them with ls.audit_maintenance = ''on''; UPDATE is refused (ADR 0020).';

-- Immutability with a single application role (ADR 0020): a trigger refuses every UPDATE
-- and every DELETE that is not part of a maintenance transaction. The retention job and the
-- erasure tool run SET LOCAL ls.audit_maintenance = 'on' before deleting. Production may
-- additionally revoke UPDATE/DELETE from the application role (documented in the ADR).
CREATE FUNCTION audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'audit_log_immutable: % is append-only, UPDATE refused', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF current_setting('ls.audit_maintenance', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'audit_log_immutable: % is append-only, DELETE refused outside a maintenance transaction', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION audit_log_immutable() IS
    'Refuses UPDATE always and DELETE unless ls.audit_maintenance = ''on'' in the current transaction (ADR 0020).';

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

CREATE TRIGGER trg_audit_log_detail_immutable
    BEFORE UPDATE OR DELETE ON audit_log_detail
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
