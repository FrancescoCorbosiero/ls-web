package it.logisticasanchez.web.site.notices;

/** How a notice of the public site looks (column {@code site_notice.level}, V021; ENH-17). */
public enum NoticeLevel {
    /** News, closures announced in advance. */
    INFO,
    /** Something the visitor should know now: delays, an unexpected closure. */
    WARNING
}
