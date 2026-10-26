-- V021: the notice banner of the public site (chapter 09 ENH-17, flag site.noticeBanner; ADR 0041).
-- An ADMIN writes a short notice (closures, delays, news) with an optional link, a level and an
-- optional period; the public header shows the notice in force, rendered by the server (no
-- inline style or script). Ending a notice keeps its row; deleting it removes it.

CREATE TABLE site_notice (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message     varchar(300)  NOT NULL,
    link_url    varchar(300),
    link_label  varchar(60),
    level       varchar(10)   NOT NULL DEFAULT 'INFO',
    starts_at   timestamptz,
    ends_at     timestamptz,
    created_by  bigint        NOT NULL REFERENCES staff_user (id),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_site_notice_level CHECK (level IN ('INFO', 'WARNING')),
    CONSTRAINT ck_site_notice_message CHECK (btrim(message) <> ''),
    CONSTRAINT ck_site_notice_period CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at),
    CONSTRAINT ck_site_notice_link CHECK ((link_url IS NULL) = (link_label IS NULL))
);

CREATE INDEX ix_site_notice_period ON site_notice (ends_at, starts_at);

COMMENT ON TABLE site_notice IS
    'Notices of the public site (ENH-17, ADR 0041): the one in force (started, not ended; the latest start first) is shown in the header of every public page.';
COMMENT ON COLUMN site_notice.link_url IS 'A path of the site (/…) or an https:// address; the label is the text of the link.';
COMMENT ON COLUMN site_notice.starts_at IS 'Null: in force from its creation.';
COMMENT ON COLUMN site_notice.ends_at IS 'Null: in force until an ADMIN ends it.';
