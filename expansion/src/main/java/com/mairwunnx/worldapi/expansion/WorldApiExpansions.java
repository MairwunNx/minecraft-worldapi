package com.mairwunnx.worldapi.expansion;

import java.util.ArrayList;
import java.util.List;

/**
 * Where expansions meet WorldAPI. Call {@link #register} from your plugin's {@code onEnable} or your mod's server start,
 * only when WorldAPI is installed: the classes of this API come with WorldAPI, so without it they aren't there.
 *
 * <pre>{@code
 * if (getServer().getPluginManager().getPlugin("WorldAPI") != null) WorldApiExpansions.register(new BossesExpansion());
 * }</pre>
 *
 * Instead, name your class in {@code META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion}: WorldAPI
 * finds it in every plugin and mod by itself, and nothing in your code needs WorldAPI to be there. Either way your
 * plugin needs WorldAPI's classes: on Paper, list WorldAPI in {@code depend} or {@code softdepend}, or for a Paper plugin
 * under {@code dependencies.server} with {@code join-classpath: true}.
 */
public final class WorldApiExpansions {
    /** What WorldAPI plugs in when it starts. Not for expansions: they call the static methods. */
    public interface Host {
        void register(WorldApiExpansion expansion);

        void unregister(String id);

        /** Takes one event of a registered expansion; throws for an event the expansion didn't list. */
        void publish(String id, Event event);

        /** The version of the API this WorldAPI implements. */
        String apiVersion();
    }

    private static final List<WorldApiExpansion> registered = new ArrayList<>();
    private static Host host;

    private WorldApiExpansions() {}

    /**
     * Adds an expansion, or replaces the one with its id. It stays registered until {@link #unregister}: a WorldAPI that
     * starts later gets it too. A broken expansion (a bad id, an id another plugin owns, a newer API than this WorldAPI's)
     * is refused with a line in the server's log.
     */
    public static synchronized void register(WorldApiExpansion expansion) {
        if (expansion == null) throw new IllegalArgumentException("expansion is null");
        String id = expansion.id();
        registered.removeIf(it -> it.id().equals(id));
        registered.add(expansion);
        if (host != null) host.register(expansion);
    }

    /** Takes an expansion out, for a plugin that's being disabled. WorldAPI does it by itself when the plugin stops. */
    public static synchronized void unregister(String id) {
        registered.removeIf(it -> it.id().equals(id));
        if (host != null) host.unregister(id);
    }

    /**
     * Sends an event of the expansion {@code id} to WorldAPI's clients and, when its type is recorded, to the history.
     * Safe on any thread. Returns false, and drops the event, while WorldAPI isn't running or the expansion isn't
     * registered; throws {@link IllegalArgumentException} for an event the expansion doesn't list.
     */
    public static boolean publish(String id, Event event) {
        if (event == null) throw new IllegalArgumentException("event is null");
        Host current;
        synchronized (WorldApiExpansions.class) {
            current = host;
            if (current == null || registered.stream().noneMatch(it -> it.id().equals(id))) return false;
        }
        current.publish(id, event);
        return true;
    }

    /** The API version of the WorldAPI running now; {@link ExpansionApi#VERSION} before WorldAPI has started. */
    public static synchronized String apiVersion() {
        return host != null ? host.apiVersion() : ExpansionApi.VERSION;
    }

    /** Whether WorldAPI runs and takes expansions now. */
    public static synchronized boolean available() {
        return host != null;
    }

    /** Every expansion registered now, whether or not WorldAPI runs. */
    public static synchronized List<WorldApiExpansion> registered() {
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
