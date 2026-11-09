package com.mairwunnx.worldapi.expansion.v2;

import java.util.UUID;

/**
 * A player in an event or an answer, written as {@code {"uuid": "…", "name": "…"}} like WorldAPI's own. An event that
 * holds one, at any depth, reaches only clients that may see the player: WorldAPI hides it while the player is vanished.
 * The first one among an event's fields is the player the history files it under.
 */
public record PlayerRef(UUID uuid, String name) {
    public PlayerRef {
        if (uuid == null) throw new IllegalArgumentException("A player needs a UUID");
    }
}
