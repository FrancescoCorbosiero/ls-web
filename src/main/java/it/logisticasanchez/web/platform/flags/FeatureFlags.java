package it.logisticasanchez.web.platform.flags;

/** The only way code reads a flag (ADR 0025). A disabled flag behaves exactly as M1. */
public interface FeatureFlags {

    boolean isEnabled(FlagKey key);
}
