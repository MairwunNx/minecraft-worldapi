package com.mairwunnx.worldapi.expansion.v2;

/** One read of a {@link Resource}: its parameters and who asks. Safe on any thread. */
public interface Request {
    /**
     * A parameter of the path or the query, read as its declaration says: {@code req[limit]} in Kotlin. A value that
     * doesn't fit answers 400, a player WorldAPI doesn't know 404. Throws {@link IllegalStateException} for a parameter
     * the resource didn't declare.
     */
    <T> T get(Param<T> param);

    /** True for a visitor without a token. */
    boolean anonymous();

    /**
     * Whether this client may see where players and things are. WorldAPI leaves every {@link Place} out by itself, so
     * check this only for coordinates you keep in other types.
     */
    boolean seesCoordinates();

    /** Whether the client holds one of WorldAPI's permissions. */
    boolean can(Permission permission);
}
