package it.logisticasanchez.web.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An accessory service of an order (table {@code accessory_service}, V004, seeded in V009;
 * ADM-10): ids 0–5 are the legacy enumeration served unchanged by API-02; item 0 is the «none»
 * entry. ENH-14 adds rows later.
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
}
