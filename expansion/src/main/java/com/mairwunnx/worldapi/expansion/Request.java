package com.mairwunnx.worldapi.expansion;

import java.util.Map;

/** One read of a {@link Resource}: its path parameters, the query, and who asks. Safe on any thread. */
public interface Request {
    /** A path parameter, like {@code player} in {@code players/{player}}. Throws for a name the path doesn't have. */
    String path(String name);

    /** A query parameter, like {@code limit} in {@code ?limit=5}; null when it's missing or empty. */
    String query(String name);

    /**
     * A whole number from the query, {@code fallback} when it's missing, kept between {@code min} and {@code max}. A value
     * that isn't a number answers 400.
     */
    default int query(String name, int fallback, int min, int max) {
        String raw = query(name);
        if (raw == null) return fallback;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Every parameter at once: the query, and the path parameters over it. */
    Map<String, String> parameters();

    /** True for a visitor without a token. */
    boolean anonymous();

    /**
     * Whether the client holds one of WorldAPI's permissions, like {@code players:private}; false for one WorldAPI
     * doesn't know. The wiki's “Tokens and permissions” lists them.
     */
    boolean can(String permission);

    /**
     * Whether this client may see where players and things are: with {@code players:private}, or as a visitor when the
     * server's owner opened coordinates to them. Leave coordinates out when it's false.
     */
    boolean seesCoordinates();
}
