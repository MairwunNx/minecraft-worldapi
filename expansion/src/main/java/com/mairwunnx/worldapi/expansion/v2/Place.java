package com.mairwunnx.worldapi.expansion.v2;

/**
 * Where something is: the world by its folder name, like {@code world_nether}, and the coordinates. WorldAPI leaves
 * every place out for clients that mustn't see coordinates, in events and in answers alike, so an expansion never checks
 * that itself. The first one among an event's fields names the world the history files it under.
 */
public record Place(String world, double x, double y, double z) {
    public Place {
        if (world == null) throw new IllegalArgumentException("A place needs a world");
    }
}
