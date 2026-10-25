package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Order rules consumed by the {@code orders} module (chapter 11 defaults, plan §8.1). */
@Validated
@ConfigurationProperties(prefix = "ls.orders")
public record OrdersProperties(
        @DefaultValue Amount amount,
        @DefaultValue Code code,
        @DefaultValue TransportDocument transportDocument,
        @DefaultValue Lines lines,
        /** Q-12: a sent order is changed or cancelled only by asking the office, while pending. */
        @NotNull @DefaultValue("OFFICE_ONLY") CustomerChanges customerChanges // Q-12
        ) {

    public enum CustomerChanges {
        OFFICE_ONLY,
        SELF_SERVICE
    }

    /** Q-05: the amount is the price charged to the customer, VAT excluded, flag stored per order. */
    public record Amount(@DefaultValue("false") boolean vatIncludedDefault // Q-05
            ) {}

    /** Q-06: staff type the definitive code; nothing is proposed. */
    public record Code(@DefaultValue("false") boolean propose // Q-06
            ) {}

    /** Q-24: the transport document code is mandatory for new orders. */
    public record TransportDocument(@DefaultValue("true") boolean required // Q-24
            ) {}

    /** Q-35: integer line values ≥ 1, no maximum, warnings above 240 cm and 1,200 kg per unit. */
    public record Lines(
            @DefaultValue("true") boolean integersOnly, // Q-35
            @Min(1) @DefaultValue("240") int warnHeightCm, // Q-35
            @Min(1) @DefaultValue("1200") int warnWeightKg, // Q-35
            /** Hard maxima; null means none (Q-35). */
            Integer maxHeightCm, // Q-35
            Integer maxWeightKg // Q-35
            ) {}
}
