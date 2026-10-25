package it.logisticasanchez.web.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One line of the wizard (table {@code order_line}, V005; OP-R4): a type, dimensions in cm, unit weight in kg, quantity, and its legacy totals. */
@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private ShipmentOrder order;

    @Column(name = "line_no", nullable = false, columnDefinition = "smallint")
    private Short lineNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 8)
    private LineKind kind;

    @Column(name = "height_cm", nullable = false)
    private int heightCm;

    @Column(name = "length_cm", nullable = false)
    private int lengthCm;

    @Column(name = "width_cm", nullable = false)
    private int widthCm;

    @Column(name = "weight_kg", nullable = false, precision = 9, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "line_weight_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineWeightKg;

    @Column(name = "line_volume_m3", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineVolumeM3;

    protected OrderLine() {}

    OrderLine(
            ShipmentOrder order,
            int lineNo,
            LineKind kind,
            int heightCm,
            int lengthCm,
            int widthCm,
            BigDecimal weightKg,
            int quantity) {
        this.order = order;
        this.lineNo = (short) lineNo;
        this.kind = kind;
        this.heightCm = heightCm;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.weightKg = weightKg;
        this.quantity = quantity;
        this.lineWeightKg = OrderTotals.lineWeight(weightKg, quantity);
        this.lineVolumeM3 = OrderTotals.lineVolume(heightCm, lengthCm, widthCm, quantity);
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public LineKind getKind() {
        return kind;
    }

    public int getHeightCm() {
        return heightCm;
    }

    public int getLengthCm() {
        return lengthCm;
    }

    public int getWidthCm() {
        return widthCm;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineWeightKg() {
        return lineWeightKg;
    }

    public BigDecimal getLineVolumeM3() {
        return lineVolumeM3;
    }
}
