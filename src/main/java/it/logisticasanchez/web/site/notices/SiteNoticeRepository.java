package it.logisticasanchez.web.site.notices;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The notices of the public site (table {@code site_notice}, V021); used through {@link SiteNoticeService}. */
@Repository
class SiteNoticeRepository {

    private static final String COLUMNS = "id, message, link_url, link_label, level, starts_at, ends_at, created_at";

    private final JdbcClient jdbc;

    SiteNoticeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(SiteNoticeService.Draft draft, long staffUserId) {
        return jdbc.sql("insert into site_notice (message, link_url, link_label, level, starts_at, ends_at, created_by)"
                        + " values (:message, :linkUrl, :linkLabel, :level, :startsAt, :endsAt, :staff) returning id")
                .param("message", draft.message())
                .param("linkUrl", draft.linkUrl())
                .param("linkLabel", draft.linkLabel())
                .param("level", draft.level().name())
                .param("startsAt", timestamp(draft.startsAt()))
                .param("endsAt", timestamp(draft.endsAt()))
                .param("staff", staffUserId)
                .query(Long.class)
                .single();
    }

    /** Every notice, those in force or to come first, then the ended ones, newest first. */
    List<SiteNoticeView> all(Instant now) {
        return jdbc.sql("select " + COLUMNS + " from site_notice"
                        + " order by (ends_at is not null and ends_at <= :now), coalesce(starts_at, created_at) desc,"
                        + " id desc")
                .param("now", Timestamp.from(now))
                .query((rs, n) -> view(rs))
                .list();
    }

    /** The notice in force at {@code now}: the latest started one. */
    Optional<SiteNoticeView> inForce(Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.sql("select " + COLUMNS + " from site_notice"
                        + " where (starts_at is null or starts_at <= :now) and (ends_at is null or ends_at > :now)"
                        + " order by coalesce(starts_at, created_at) desc, id desc limit 1")
                .param("now", at)
                .query((rs, n) -> view(rs))
                .optional();
    }

    Optional<SiteNoticeView> find(long id) {
        return jdbc.sql("select " + COLUMNS + " from site_notice where id = :id")
                .param("id", id)
                .query((rs, n) -> view(rs))
                .optional();
    }

    /** Ends the notice now (a later start becomes the same instant, so the period stays valid). */
    int end(long id, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.sql("update site_notice set ends_at = :now,"
                        + " starts_at = case when starts_at is not null and starts_at >= :now then null else starts_at end"
                        + " where id = :id and (ends_at is null or ends_at > :now)")
                .param("now", at)
                .param("id", id)
                .update();
    }

    int delete(long id) {
        return jdbc.sql("delete from site_notice where id = :id")
                .param("id", id)
                .update();
    }

    private static SiteNoticeView view(ResultSet rs) throws SQLException {
        return new SiteNoticeView(
                rs.getLong("id"),
                rs.getString("message"),
                rs.getString("link_url"),
                rs.getString("link_label"),
                NoticeLevel.valueOf(rs.getString("level")),
                instant(rs.getTimestamp("starts_at")),
                instant(rs.getTimestamp("ends_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
