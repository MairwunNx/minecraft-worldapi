package com.mairwunnx.worldapi.expansion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One event to publish: its name, its data, and optionally the player and world it's about. Immutable: {@link #player}
 * and {@link #world} return changed copies.
 *
 * <pre>{@code
 * WorldApiExpansions.publish("mobosses", Event.of("player.level_up", Map.of("level", 12)).player(id, name).world("world"));
 * }</pre>
 */
public final class Event {
    private final String name;
    private final Map<String, Object> data;
    private final UUID player;
    private final String playerName;
    private final String world;

    private Event(String name, Map<String, Object> data, UUID player, String playerName, String world) {
        this.name = name;
        this.data = data;
        this.player = player;
        this.playerName = playerName;
        this.world = world;
    }

    /** An event of a type the expansion lists in {@link WorldApiExpansion#events()}, with plain Java values as data. */
    public static Event of(String name, Map<String, ?> data) {
        Names.check(Names.EVENT, name, "event name");
        return new Event(name, data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data)), null, null, null);
    }

    /**
     * The player the event is about. WorldAPI hides it from clients that mustn't see the player, as it does for its own
     * events about a vanished player.
     */
    public Event player(UUID id, String name) {
        if (id == null) throw new IllegalArgumentException("player is null");
        return new Event(this.name, data, id, name, world);
    }

    /** The world it happened in, by its folder name, like {@code world_nether}. */
    public Event world(String world) {
        return new Event(name, data, player, playerName, world);
    }

    public String name() {
        return name;
    }

    public Map<String, Object> data() {
        return data;
    }

    /** Null when the event isn't about one player. */
    public UUID player() {
        return player;
    }

    public String playerName() {
        return playerName;
    }

    public String world() {
        return world;
    }
}
