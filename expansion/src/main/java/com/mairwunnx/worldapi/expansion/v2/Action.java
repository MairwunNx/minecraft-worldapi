package com.mairwunnx.worldapi.expansion.v2;

import java.util.ArrayList;
import java.util.List;

/**
 * One thing clients may do through your plugin, like spawning a boss or putting a player into AFK: {@code POST} or
 * {@code DELETE /extensions/<id>/<path>}. An action needs a token with its {@link #permission}, never answers a visitor
 * without one, and lands in WorldAPI's audit log. Since API 2.1.
 *
 * <pre>{@code
 * val spawn = action("bosses/spawn", "Spawns a boss", ::spawn).body(type, level, at).permission(Permission.WORLD_WRITE).onServerThread()
 * }</pre>
 */
public final class Action {
    /** The HTTP method a client calls it with. */
    public enum Method { POST, DELETE }

    private final Expansion expansion;
    private final Method method;
    private final String path;
    private final String description;
    private final Resource.Handler handler;
    private final List<Param<?>> pathParams;
    private final List<Param<?>> query = new ArrayList<>();
    private final List<Param<?>> body = new ArrayList<>();
    private Permission permission;
    private final Overrides overrides = new Overrides();
    private boolean serverThread;
    private Class<?> returns;
    private boolean returnsList;

    Action(Expansion expansion, Method method, String path, String description, Resource.Handler handler, List<Param<?>> pathParams) {
        if (handler == null) throw new IllegalArgumentException("The action " + path + " needs a handler");
        this.expansion = expansion;
        this.method = method;
        this.path = path;
        this.description = Names.text(description);
        this.handler = handler;
        this.pathParams = List.copyOf(pathParams);
    }

    /** The fields of the JSON body it reads, declared on the expansion. A {@code DELETE} takes none: use {@link #query}. */
    public Action body(Param<?>... params) {
        if (method == Method.DELETE) throw new IllegalArgumentException(path + " is a DELETE, which takes its parameters from the query");
        add(body, params);
        return this;
    }

    /** The query parameters it reads, declared on the expansion. */
    public Action query(Param<?>... params) {
        add(query, params);
        return this;
    }

    /** The client must send these to this one, though others may read them as optional. Since API 2.1. */
    public Action required(Param<?>... params) {
        expansion.checkOpen();
        overrides.required(path, params(), params);
        return this;
    }

    /** What a missing value reads as here, whatever the parameter's own fallback: {@code .orElse(limit, 10)}. Since API 2.1. */
    public <V> Action orElse(Param<V> param, V fallback) {
        expansion.checkOpen();
        overrides.fallback(path, params(), param, fallback);
        return this;
    }

    /** A whole number's range here, whatever the parameter's own: {@code .between(limit, 1, 50)}. Since API 2.1. */
    public Action between(Param<Integer> param, int min, int max) {
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
     * The permission a client needs to call it, like {@link Permission#WORLD_WRITE} for one that changes the world or
     * {@link Permission#PLAYERS_MODERATE} for one that acts on another player. Every action names one: WorldAPI refuses
     * an expansion with an action that doesn't.
     */
    public Action permission(Permission permission) {
        expansion.checkOpen();
        this.permission = permission;
        return this;
    }

    /** Runs the handler on the server thread, for one that changes the world, entities, or players. Keep it quick there. */
    public Action onServerThread() {
        expansion.checkOpen();
        serverThread = true;
        return this;
    }

    /** The record it answers with, for the schema in {@code GET /extensions}. */
    public Action returns(Class<? extends Record> type) {
        expansion.checkOpen();
        returns = type;
        returnsList = false;
        return this;
    }

    /** It answers with a list of these records, for the schema in {@code GET /extensions}. */
    public Action returnsListOf(Class<? extends Record> type) {
        expansion.checkOpen();
        returns = type;
        returnsList = true;
        return this;
    }

    private void add(List<Param<?>> into, Param<?>... params) {
        expansion.checkOpen();
        for (Param<?> param : params) {
            if (param == null || param.expansion() != expansion) throw new IllegalArgumentException("Declare the parameters of " + path + " on " + expansion.id());
            if (pathParams.contains(param) || query.contains(param) || body.contains(param)) throw new IllegalArgumentException(path + " reads " + param.name() + " twice");
            if (param.kind() == Param.Kind.PLACE && into != body) throw new IllegalArgumentException(param.name() + " is a place, which only a body carries");
            into.add(param);
        }
    }

    public Expansion expansion() {
        return expansion;
    }

    public Method method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String description() {
        return description;
    }

    public Resource.Handler handler() {
        return handler;
    }

    /** Its path parameters, then its query parameters, then its body's fields. */
    public List<Param<?>> params() {
        List<Param<?>> all = new ArrayList<>(pathParams);
        all.addAll(query);
        all.addAll(body);
        return List.copyOf(all);
    }

    public List<Param<?>> pathParams() {
        return pathParams;
    }

    public List<Param<?>> queryParams() {
        return List.copyOf(query);
    }

    public List<Param<?>> bodyParams() {
        return List.copyOf(body);
    }

    /** Null until {@link #permission} names one. */
    public Permission permission() {
        return permission;
    }

    public boolean serverThread() {
        return serverThread;
    }

    public Class<?> returns() {
        return returns;
    }

    public boolean returnsList() {
        return returnsList;
    }
}
