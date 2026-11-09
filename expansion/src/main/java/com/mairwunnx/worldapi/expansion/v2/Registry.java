package com.mairwunnx.worldapi.expansion.v2;

import java.util.ArrayList;
import java.util.List;

/**
 * Where expansions meet WorldAPI. Expansions call {@link Expansion#register()} and {@link Event#publish}, which land
 * here; WorldAPI binds itself as the {@link Host} when it starts.
 */
public final class Registry {
    /** What WorldAPI plugs in when it starts. Not for expansions. */
    public interface Host {
        void register(Expansion expansion);

        void unregister(String id);

        /** Takes one event of a registered expansion. */
        void publish(Event<?> event, Record data);
    }

    private static final List<Expansion> registered = new ArrayList<>();
    private static Host host;

    private Registry() {}

    static synchronized void register(Expansion expansion) {
        expansion.freeze();
        registered.removeIf(it -> it.id().equals(expansion.id()));
        registered.add(expansion);
        if (host != null) host.register(expansion);
    }

    /** Takes the expansion {@code id} out, as {@link Expansion#unregister()} does. WorldAPI calls it when a plugin stops. */
    public static synchronized void unregister(String id) {
        registered.removeIf(it -> it.id().equals(id));
        if (host != null) host.unregister(id);
    }

    static boolean publish(Event<?> event, Record data) {
        if (data == null) throw new IllegalArgumentException("The event " + event.name() + " needs its data");
        if (!event.payload().isInstance(data)) throw new IllegalArgumentException("The event " + event.name() + " takes " + event.payload().getName());
        String id = event.expansion().id();
        Host current;
        synchronized (Registry.class) {
            current = host;
            if (current == null || registered.stream().noneMatch(it -> it.id().equals(id))) return false;
        }
        current.publish(event, data);
        return true;
    }

    /** Whether WorldAPI runs and takes expansions now. */
    public static synchronized boolean available() {
        return host != null;
    }

    /** Every expansion registered now, whether or not WorldAPI runs. */
    public static synchronized List<Expansion> registered() {
        return List.copyOf(registered);
    }

    /**
     * WorldAPI starts taking expansions, every one registered so far first; null when it stops. WorldAPI calls this; an
     * expansion never does.
     */
    public static synchronized void bind(Host worldApi) {
        host = worldApi;
        if (worldApi != null) List.copyOf(registered).forEach(worldApi::register);
    }
}
