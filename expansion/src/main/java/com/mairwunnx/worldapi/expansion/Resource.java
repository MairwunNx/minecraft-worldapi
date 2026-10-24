package com.mairwunnx.worldapi.expansion;

/**
 * One path clients read, like {@code players/{player}}, and the handler that answers it. WorldAPI calls the handler on
 * every {@code GET /extensions/<id>/<path>}, off the server thread unless {@link #onServerThread()} asks for it.
 *
 * <pre>{@code
 * Resource.of("players/{player}", "A player's card: level and kills", request -> card(request.path("player")))
 *     .publicly()
 * }</pre>
 *
 * Instances are immutable: {@link #publicly()} and {@link #onServerThread()} return changed copies.
 */
public final class Resource {
    /**
     * Answers a request with plain Java values, which WorldAPI writes as JSON: maps, lists and arrays, strings, numbers,
     * booleans, and null; UUIDs, enums, and times become strings, anything else its {@code toString()}. A
     * {@code CompletionStage} of such a value lets you finish on your own thread, a database's or the network's;
     * WorldAPI waits up to 10 seconds.
     *
     * <p>Throw {@link IllegalArgumentException} for a bad request (400) and {@link java.util.NoSuchElementException} for
     * something that isn't there (404), each with a short English message for the client; any other exception reaches
     * the client as 502 with its message.
     */
    @FunctionalInterface
    public interface Handler {
        Object read(Request request) throws Exception;
    }

    private final String path;
    private final String description;
    private final boolean isPublic;
    private final boolean serverThread;
    private final Handler handler;

    private Resource(String path, String description, boolean isPublic, boolean serverThread, Handler handler) {
        this.path = path;
        this.description = description;
        this.isPublic = isPublic;
        this.serverThread = serverThread;
        this.handler = handler;
    }

    /**
     * A resource only clients with a token read. Each path segment is lower-case letters, digits, underscores, and
     * hyphens, or a parameter in braces, like {@code {player}}, which {@link Request#path} gives back.
     */
    public static Resource of(String path, String description, Handler handler) {
        if (handler == null) throw new IllegalArgumentException("The resource " + path + " needs a handler");
        String trimmed = Names.check(Names.PATH, path, "resource path").replaceAll("^/|/$", "");
        return new Resource(trimmed, Names.text(description), false, false, handler);
    }

    /**
     * Visitors without a token may read it too, unless the server's owner hides it in WorldAPI's config. Make public only
     * what anyone may see: never coordinates, inventories, addresses, or chat, unless {@link Request#seesCoordinates()}
     * and the like say this client may.
     */
    public Resource publicly() {
        return new Resource(path, description, true, serverThread, handler);
    }

    /**
     * Runs the handler on the server thread, for one that reads the world, entities, or players. Keep it quick there,
     * and never wait on the network or a database: return a {@code CompletionStage} instead.
     */
    public Resource onServerThread() {
        return new Resource(path, description, isPublic, true, handler);
    }

    public String path() {
        return path;
    }

    public String description() {
        return description;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean serverThread() {
        return serverThread;
    }

    public Handler handler() {
        return handler;
    }
}
