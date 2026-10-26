package it.logisticasanchez.web.platform.flags;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every M2 feature flag of chapter 09 §M2 (ADR 0025): one constant per seeded
 * {@code feature_flag} row. A key missing from the table fails the start-up, so the table and
 * this enum are checked against each other. {@link #built()} says whether the feature behind the
 * flag exists in this code: the settings screen lets an ADMIN turn on only those.
 */
public enum FlagKey {
    PRICING_LISTS("pricing.lists", "ENH-01", false),
    PRICING_SUGGESTED_AMOUNT("pricing.suggestedAmount", "ENH-02", false),
    PRICING_CUSTOMER_ESTIMATE("pricing.customerEstimate", "ENH-03", false),
    PRICING_PUBLIC_ESTIMATE("pricing.publicEstimate", "ENH-04", false),
    TRACKING_HISTORY("tracking.history", "ENH-05", false),
    TRACKING_NOTIFY_CUSTOMER("tracking.notifyCustomer", "ENH-05", false),
    LEADS_INBOX("leads.inbox", "ENH-06", false),
    CUSTOMERS_ADDRESS_BOOK("customers.addressBook", "ENH-07", false),
    ORDERS_REPEAT("orders.repeat", "ENH-07", false),
    ADMIN_DASHBOARD("admin.dashboard", "ENH-08", false),
    ADMIN_EXPORTS("admin.exports", "ENH-08", false),
    SECURITY_ADMIN_TOTP("security.adminTotp", "ENH-09", false),
    DELIVERY_UNIFIED_TIMES("delivery.unifiedTimes", "ENH-10", false),
    PRICING_MARGIN_VIEW("pricing.marginView", "ENH-11", false),
    DOCUMENTS("documents", "ENH-12", false),
    ADMIN_AUDIT("admin.audit", "ENH-13", false),
    ADMIN_ENUMERATIONS("admin.enumerations", "ENH-14", false),
    ORDERS_BULK_TRACKING("orders.bulkTracking", "ENH-15", false),
    CUSTOMERS_PROFILE_EDIT("customers.profileEdit", "ENH-16", false),
    SITE_NOTICE_BANNER("site.noticeBanner", "ENH-17", false),
    SITE_OPEN_NOW("site.openNow", "ENH-18", false),
    SITE_PICKUP_CUTOFF("site.pickupCutoff", "ENH-19", false),
    QUOTES_SAVED("quotes.saved", "ENH-20", false),
    DOCUMENTS_MONTHLY_REPORT("documents.monthlyReport", "ENH-21", false),
    SITE_LIVE_COUNTERS("site.liveCounters", "ENH-22", false);

    private final String key;
    private final String requirementId;
    private final boolean built;

    FlagKey(String key, String requirementId, boolean built) {
        this.key = key;
        this.requirementId = requirementId;
        this.built = built;
    }

    /** The {@code feature_flag.key} value. */
    public String key() {
        return key;
    }

    public String requirementId() {
        return requirementId;
    }

    /** True once the feature behind the flag is built («Disponibile»); false = «Non ancora sviluppata». */
    public boolean built() {
        return built;
    }

    public static Optional<FlagKey> ofKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
