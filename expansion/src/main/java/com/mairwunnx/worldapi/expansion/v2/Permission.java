package com.mairwunnx.worldapi.expansion.v2;

import java.util.Objects;

/**
 * One of WorldAPI's permissions, for {@link Request#can}. The wiki's “Tokens and permissions” says what each one opens.
 * {@link #of} names one this version of the API doesn't list yet.
 */
public final class Permission {
    public static final Permission SERVER_READ = new Permission("server:read");
    public static final Permission PLAYERS_READ = new Permission("players:read");
    public static final Permission PLAYERS_PRIVATE = new Permission("players:private");
    public static final Permission EVENTS_READ = new Permission("events:read");
    public static final Permission CHAT_READ = new Permission("chat:read");
    public static final Permission LOGS_READ = new Permission("logs:read");
    public static final Permission SYSTEM_READ = new Permission("system:read");
    public static final Permission WORLD_WRITE = new Permission("world:write");
    public static final Permission PLAYERS_WRITE = new Permission("players:write");
    public static final Permission PLAYERS_MODERATE = new Permission("players:moderate");
    public static final Permission SERVER_CONSOLE = new Permission("server:console");
    public static final Permission COMMANDS_READ = new Permission("commands:read");
    public static final Permission SERVER_CONTROL = new Permission("server:control");
    public static final Permission FILES_READ = new Permission("files:read");
    public static final Permission FILES_WRITE = new Permission("files:write");
    public static final Permission TOKENS_MANAGE = new Permission("tokens:manage");
    public static final Permission WEBHOOKS_MANAGE = new Permission("webhooks:manage");

    private final String id;

    private Permission(String id) {
        this.id = id;
    }

    /** A permission by its id, like {@code players:private}. A client never holds one WorldAPI doesn't know. */
    public static Permission of(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("A permission needs an id");
        return new Permission(id);
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Permission permission && permission.id.equals(id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
