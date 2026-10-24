package it.logisticasanchez.web.support;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Typed settings records with their {@code @DefaultValue}s, for unit tests without a Spring context. */
public final class Settings {

    private Settings() {}

    public static <T> T defaults(String prefix, Class<T> type) {
        return with(prefix, type, Map.of());
    }

    public static <T> T with(String prefix, Class<T> type, Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(prefix, Bindable.of(type));
    }
}
