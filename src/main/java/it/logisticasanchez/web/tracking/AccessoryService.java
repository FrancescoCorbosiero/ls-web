package it.logisticasanchez.web.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An accessory service (table {@code accessory_service}, V004, seeded in V009; ADM-10): ids 0–5
 * are the legacy enumeration of the order served unchanged by API-02; item 0 is the «none» entry.
 * ENH-14 (V023, ADR 0043) says where each row shows — the order, {@code /servizi/}, the quote
 * wizard — and adds the rows of the two public lists; the back office manages them through
 * {@link AccessoryServiceAdmin}.
 */
@Entity
@Table(name = "accessory_service")
public class AccessoryService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "is_none", nullable = false)
    private boolean none;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "in_orders", nullable = false)
    private boolean inOrders;

    @Column(name = "on_site", nullable = false)
    private boolean onSite;

    @Column(name = "in_quote", nullable = false)
    private boolean inQuote;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "icon", length = 40)
    private String icon;

    @Column(name = "quote_hint", length = 120)
    private String quoteHint;

    protected AccessoryService() {}

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public boolean isNone() {
        return none;
    }

    public boolean isActive() {
        return active;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public boolean isInOrders() {
        return inOrders;
    }

    public boolean isOnSite() {
        return onSite;
    }

    public boolean isInQuote() {
        return inQuote;
    }

    public String getDescription() {
        return description;
    }

    public String getIcon() {
        return icon;
    }

    public String getQuoteHint() {
        return quoteHint;
    }
}
