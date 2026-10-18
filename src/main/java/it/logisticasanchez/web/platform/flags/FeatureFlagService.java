package it.logisticasanchez.web.platform.flags;

import it.logisticasanchez.web.platform.audit.AuditActorType;
import it.logisticasanchez.web.platform.audit.AuditEntry;
import it.logisticasanchez.web.platform.audit.AuditService;
import it.logisticasanchez.web.platform.config.FlagsProperties;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature flags over the {@code feature_flag} table (ADR 0025): reads cached for
 * {@code ls.flags.cache-seconds}, evicted at once by a toggle made here; environment overrides
 * ({@code ls.flags.override.<key>}) for tests and local runs, refused in production; the
 * ADMIN-only {@link #toggle} writes the row and the audit entry {@code flag.toggle}.
 */
@Service
public class FeatureFlagService implements FeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository repository;
    private final AuditService audit;
    private final Clock clock;
    private final Duration cachePeriod;
    private final Map<FlagKey, Boolean> overrides;
    private final boolean production;
    private final Map<FlagKey, Cached> cache = new ConcurrentHashMap<>();

    public FeatureFlagService(
            FeatureFlagRepository repository,
            AuditService audit,
            FlagsProperties properties,
            Environment environment,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.cachePeriod = Duration.ofSeconds(properties.cacheSeconds());
        this.overrides = parseOverrides(properties.override());
        this.production = environment.matchesProfiles("prod");
    }

    static Map<FlagKey, Boolean> parseOverrides(Map<String, Boolean> raw) {
        Map<FlagKey, Boolean> parsed = new EnumMap<>(FlagKey.class);
        raw.forEach((key, value) -> parsed.put(
                FlagKey.ofKey(key).orElseThrow(() -> new IllegalStateException("ls.flags.override: unknown flag " + key)),
                Objects.requireNonNull(value, "override value of " + key)));
        return parsed;
    }

    @PostConstruct
    void verify() {
        if (production && !overrides.isEmpty()) {
            throw new IllegalStateException("ls.flags.override.* is refused in production (ADR 0025)");
        }
        Set<String> seeded = repository.findAll().stream().map(FeatureFlag::getKey).collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (FlagKey key : FlagKey.values()) {
            if (!seeded.contains(key.key())) {
                missing.add(key.key());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("feature_flag rows missing for " + missing + " (ADR 0025)");
        }
        overrides.forEach((key, value) -> log.info("feature flag override: {} = {}", key.key(), value));
    }

    @Override
    public boolean isEnabled(FlagKey key) {
        Boolean override = overrides.get(key);
        if (override != null) {
            return override;
        }
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && cached.validUntil.isAfter(now)) {
            return cached.enabled;
        }
        boolean enabled = repository
                .findById(key.key())
                .map(FeatureFlag::isEnabled)
                .orElseThrow(() -> new IllegalStateException("feature_flag row missing: " + key.key()));
        cache.put(key, new Cached(enabled, now.plus(cachePeriod)));
        return enabled;
    }

    /** ADMIN only (NFR-02): writes the row and the audit entry, evicts the cache. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void toggle(FlagKey key, boolean enabled, long staffUserId) {
        FeatureFlag flag = repository
                .findById(key.key())
                .orElseThrow(() -> new IllegalStateException("feature_flag row missing: " + key.key()));
        boolean before = flag.isEnabled();
        Instant now = clock.instant();
        flag.setEnabled(enabled, staffUserId, now);
        repository.save(flag);
        audit.record(AuditEntry.of(AuditActorType.STAFF, staffUserId, "flag.toggle")
                .entity("feature_flag", null, key.key())
                .change(Map.of("enabled", before), Map.of("enabled", enabled)));
        cache.remove(key);
    }

    /** Every flag with its current value (the back-office screen, Phase 2). */
    public Map<FlagKey, Boolean> all() {
        Map<FlagKey, Boolean> values = new EnumMap<>(FlagKey.class);
        for (FlagKey key : FlagKey.values()) {
            values.put(key, isEnabled(key));
        }
        return values;
    }

    private record Cached(boolean enabled, Instant validUntil) {}
}
