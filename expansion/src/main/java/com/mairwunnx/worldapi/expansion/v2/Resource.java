package com.mairwunnx.worldapi.expansion.v2;

import java.util.ArrayList;
import java.util.List;

/**
 * One path clients read, like {@code players/{player}}, and the handler that answers it. WorldAPI calls the handler on
 * every {@code GET /extensions/<id>/<path>}, off the server thread unless {@link #onServerThread()} asks for it.
 */
public final class Resource {
    /**
     * Answers a request with records or plain Java values, which WorldAPI writes as JSON: records by their components,
     * maps, lists and arrays, strings, numbers, booleans, and null; UUIDs, enums, and times become strings, anything else
     * its {@code toString()}. A {@code CompletionStage} of such a value lets you finish on your own thread, a database's
     * or the network's. WorldAPI waits up to 10 seconds.
     *
     * <p>Throw {@link IllegalArgumentException} for a bad request (400) and {@link java.util.NoSuchElementException} for
     * something that isn't there (404), each with a short English message for the client. Any other exception reaches
     * the client as 502 with its message.
     */
    @FunctionalInterface
    public interface Handler {
        Object read(Request request) throws Exception;
    }

    private final Expansion expansion;
    private final String path;
    private final String description;
    private final Handler handler;
    private final List<Param<?>> params = new ArrayList<>();
    private boolean isPublic;
    private final Overrides overrides = new Overrides();
    private boolean serverThread;
    private Class<?> returns;
    private boolean returnsList;

    Resource(Expansion expansion, String path, String description, Handler handler, List<Param<?>> pathParams) {
        if (handler == null) throw new IllegalArgumentException("The resource " + path + " needs a handler");
        this.expansion = expansion;
        this.path = path;
        this.description = Names.text(description);
        this.handler = handler;
        params.addAll(pathParams);
    }

    /** The query parameters it reads, declared on the expansion. */
    public Resource query(Param<?>... params) {
        expansion.checkOpen();
        for (Param<?> param : params) {
            if (param == null || param.expansion() != expansion) throw new IllegalArgumentException("Declare the parameters of " + path + " on " + expansion.id());
            if (this.params.contains(param)) throw new IllegalArgumentException(path + " reads " + param.name() + " twice");
            this.params.add(param);
        }
        return this;
    }

    /** The client must send these to this one, though others may read them as optional. Since API 2.1. */
    public Resource required(Param<?>... params) {
        expansion.checkOpen();
        overrides.required(path, params(), params);
        return this;
    }

    /** What a missing value reads as here, whatever the parameter's own fallback: {@code .orElse(limit, 10)}. Since API 2.1. */
    public <V> Resource orElse(Param<V> param, V fallback) {
        expansion.checkOpen();
        overrides.fallback(path, params(), param, fallback);
        return this;
    }

    /** A whole number's range here, whatever the parameter's own: {@code .between(limit, 1, 50)}. Since API 2.1. */
    public Resource between(Param<Integer> param, int min, int max) {
        expansion.checkOpen();
        overrides.range(path, params(), param, min, max);
        return this;
    }

    /** Whether a request here must carry {@code param}. */
    public boolean requires(Param<?> param) {
        return overrides.requires(param);
    }

    /** What a missing {@code param} reads as here. */
    public Object fallbackOf(Param<?> param) {
        return overrides.fallbackOf(param);
    }

    /** The smallest whole number {@code param} takes here. */
    public long minOf(Param<?> param) {
        return overrides.minOf(param);
    }

    /** The largest whole number {@code param} takes here. */
    public long maxOf(Param<?> param) {
        return overrides.maxOf(param);
    }

    /** Reads a client's text for {@code param} as this one asks. */
    public <V> V parse(Param<V> param, String raw) {
        return overrides.parse(param, raw);
    }

    /**
     * Visitors without a token may read it too, unless the server's owner hides it in WorldAPI's config. Make public only
     * what anyone may see: never inventories, addresses, or chat. WorldAPI leaves out every {@link Place} by itself.
     */
    public Resource publicly() {
        expansion.checkOpen();
        isPublic = true;
        return this;
    }

    /**
     * Runs the handler on the server thread, for one that reads the world, entities, or players. Keep it quick there,
     * and never wait on the network or a database: return a {@code CompletionStage} instead.
     */
    public Resource onServerThread() {
        expansion.checkOpen();
        serverThread = true;
        return this;
    }

    /** The record it answers with, for the schema in {@code GET /extensions}. */
    public Resource returns(Class<? extends Record> type) {
        expansion.checkOpen();
        returns = type;
        returnsList = false;
        return this;
    }

    /** It answers with a list of these records, for the schema in {@code GET /extensions}. */
    public Resource returnsListOf(Class<? extends Record> type) {
        expansion.checkOpen();
        returns = type;
        returnsList = true;
        return this;
    }

    public Expansion expansion() {
        return expansion;
    }

    public String path() {
        return path;
    }

    public String description() {
        return description;
    }

    public Handler handler() {
        return handler;
    }

    /** Its path parameters, then its query parameters. */
    public List<Param<?>> params() {
        return List.copyOf(params);
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean serverThread() {
        return serverThread;
    }

    /** The record it answers with, or null when it didn't say. */
    public Class<?> returns() {
        return returns;
    }

    /** Whether it answers with a list of {@link #returns()}. */
    public boolean returnsList() {
        return returnsList;
    }
}
