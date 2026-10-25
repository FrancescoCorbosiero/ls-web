package it.logisticasanchez.web.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One of the six tracking states of the legacy backend (table {@code tracking_state}, V004,
 * seeded in V009; ADM-10): the legacy id, name and label served by the compatibility API
 * (API-02) and the public title and step label of the tracking page (chapter 06 §5.7).
 */
@Entity
@Table(name = "tracking_state")
public class TrackingState {

    /** The id of {@code NON_GESTITO}, the state of every order before approval and the «unknown» answer. */
    public static final int NON_GESTITO = 0;

    /** The id of {@code PRESA}, the state an order takes at approval (ADM-04). */
    public static final short PRESA = 1;

    /** The id of {@code CONSEGNATO}, the only state that e-mails the customer in M1 (EML-09). */
    public static final short CONSEGNATO = 5;

    @Id
    @Column(name = "id", columnDefinition = "smallint")
    private Short id;

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "public_title", nullable = false, length = 60)
    private String publicTitle;

    @Column(name = "step_label", length = 40)
    private String stepLabel;

    @Column(name = "sort_order", nullable = false, columnDefinition = "smallint")
    private Short sortOrder;

    protected TrackingState() {}

    public Short getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public String getPublicTitle() {
        return publicTitle;
    }

    public String getStepLabel() {
        return stepLabel;
    }

    public Short getSortOrder() {
        return sortOrder;
    }
}
