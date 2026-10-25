package it.logisticasanchez.web.platform.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Customer rules consumed by the {@code customers} and {@code auth} modules (chapter 11 defaults). */
@Validated
@ConfigurationProperties(prefix = "ls.customers")
public record CustomersProperties(
        /** Q-11: one login per customer in M1; the model allows more users later. */
        @Min(1) @DefaultValue("1") int maxUsersPerCustomer, // Q-11
        @DefaultValue Registration registration,
        @DefaultValue Approval approval) {

    public record Registration(
            @DefaultValue("true") boolean allowPrivatePersons, // Q-10
            @DefaultValue("false") boolean vatNumberRequired, // Q-10
            @DefaultValue("false") boolean taxCodeRequired, // Q-10
            @DefaultValue("true") boolean phoneRequired // Q-36
            ) {}

    public record Approval(@DefaultValue("true") boolean requiresVerifiedEmail // Q-28
            ) {}
}
