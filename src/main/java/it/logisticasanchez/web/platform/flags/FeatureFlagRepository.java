package it.logisticasanchez.web.platform.flags;

import org.springframework.data.jpa.repository.JpaRepository;

/** Feature flags; read through the cached service of the {@code platform} module (ADR 0025). */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {}
