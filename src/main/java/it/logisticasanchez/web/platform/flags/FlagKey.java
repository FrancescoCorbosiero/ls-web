package it.logisticasanchez.web.platform.flags;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every M2 feature flag of chapter 09 §M2 (ADR 0025): one constant per seeded
 * {@code feature_flag} row. A key missing from the table fails the start-up, so the table and
 * this enum are checked against each other.
 */
public enum FlagKey {
    PRICING_LISTS("pricing.lists", "ENH-01"),
    PRICING_SUGGESTED_AMOUNT("pricing.suggestedAmount", "ENH-02"),
    PRICING_CUSTOMER_ESTIMATE("pricing.customerEstimate", "ENH-03"),
    PRICING_PUBLIC_ESTIMATE("pricing.publicEstimate", "ENH-04"),
    TRACKING_HISTORY("tracking.history", "ENH-05"),
    TRACKING_NOTIFY_CUSTOMER("tracking.notifyCustomer", "ENH-05"),
    LEADS_INBOX("leads.inbox", "ENH-06"),
    CUSTOMERS_ADDRESS_BOOK("customers.addressBook", "ENH-07"),
    ORDERS_REPEAT("orders.repeat", "ENH-07"),
    ADMIN_DASHBOARD("admin.dashboard", "ENH-08"),
    ADMIN_EXPORTS("admin.exports", "ENH-08"),
    SECURITY_ADMIN_TOTP("security.adminTotp", "ENH-09"),
    DELIVERY_UNIFIED_TIMES("delivery.unifiedTimes", "ENH-10"),
    PRICING_MARGIN_VIEW("pricing.marginView", "ENH-11"),
    DOCUMENTS("documents", "ENH-12"),
    ADMIN_AUDIT("admin.audit", "ENH-13"),
    ADMIN_ENUMERATIONS("admin.enumerations", "ENH-14"),
    ORDERS_BULK_TRACKING("orders.bulkTracking", "ENH-15"),
    CUSTOMERS_PROFILE_EDIT("customers.profileEdit", "ENH-16"),
    SITE_NOTICE_BANNER("site.noticeBanner", "ENH-17"),
    SITE_OPEN_NOW("site.openNow", "ENH-18"),
    SITE_PICKUP_CUTOFF("site.pickupCutoff", "ENH-19"),
    QUOTES_SAVED("quotes.saved", "ENH-20"),
    DOCUMENTS_MONTHLY_REPORT("documents.monthlyReport", "ENH-21"),
    SITE_LIVE_COUNTERS("site.liveCounters", "ENH-22");

    private final String key;
    private final String requirementId;

    FlagKey(String key, String requirementId) {
        this.key = key;
        this.requirementId = requirementId;
    }

    /** The {@code feature_flag.key} value. */
    public String key() {
        return key;
    }

    public String requirementId() {
        return requirementId;
    }

    public static Optional<FlagKey> ofKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
