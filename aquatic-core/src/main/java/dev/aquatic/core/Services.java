package dev.aquatic.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry through which modules expose their API (dev.aquatic.api.*) to each other. */
public final class Services {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, T implementation) {
        services.put(type, implementation);
    }

    public void unregister(Class<?> type) {
        services.remove(type);
    }

    /** @return the service, or null when the providing module is disabled. */
    public <T> T get(Class<T> type) {
        return type.cast(services.get(type));
    }

    public boolean has(Class<?> type) {
        return services.containsKey(type);
    }
}
