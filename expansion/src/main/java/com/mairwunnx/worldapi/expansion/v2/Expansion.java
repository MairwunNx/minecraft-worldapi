package com.mairwunnx.worldapi.expansion.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What a plugin or mod shares through WorldAPI: resources clients read at {@code /extensions/<id>/<path>}, and event
 * topics it publishes to, {@code ext.<id>.<event>}, which reach WebSocket clients and, when recorded, the history. The
 * MCP server reads them too. Your plugin stays the source of truth: WorldAPI stores none of its data, only the recorded
 * events.
 *
 * <p>Extend it with a class or a Kotlin {@code object}, and declare what you share as its fields, the way Exposed
 * declares a table's columns:
 *
 * <pre>{@code
 * object Bosses : Expansion("mobosses", "Mo’Bosses", "Bosses, their kills, and the best hunters") {
 *     val limit = integer("limit", "How many hunters", 1, 50, 10)
 *     val hunters = resource("hunters", "The best hunters") { top(it[limit]) }.query(limit).publicly()
 *     val killed = event("boss.killed", BossKilled::class.java, "Players killed a boss").publicly().recorded()
 * }
 *
 * Bosses.register()
 * Bosses.killed.publish(BossKilled(PlayerRef(player.uniqueId, player.name), "zombie", 40))
 * }</pre>
 *
 * Call {@link #register()} once WorldAPI is installed, or name the class in
 * {@code META-INF/services/com.mairwunnx.worldapi.expansion.v2.Expansion}: WorldAPI finds it in every plugin and mod by
 * itself, a Kotlin {@code object} through its {@code INSTANCE}, a class through a constructor without parameters. On
 * Paper, list WorldAPI in {@code depend} or {@code softdepend}, or for a Paper plugin under {@code dependencies.server}
 * with {@code join-classpath: true}.
 *
 * <p>Declarations happen while the expansion is built, in field initializers or the constructor. Registered, it
 * doesn't change anymore.
 */
public abstract class Expansion {
    private final String id;
    private final String name;
    private final String description;
    private final List<Param<?>> params = new ArrayList<>();
    private final List<Resource> resources = new ArrayList<>();
    private final List<Event<?>> events = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private volatile boolean frozen;

    /**
     * @param id a short id of lower-case letters, digits, underscores, and hyphens, 1 to 32 long, like {@code mobosses}.
     *     It's the namespace in {@code /extensions/mobosses/...} and in the topics {@code ext.mobosses.*}. One plugin owns
     *     an id: a second plugin that takes it is refused.
     */
    protected Expansion(String id) {
        this(id, id, "");
    }

    /**
     * @param name the name people know your plugin or mod by, like {@code Mo’Bosses}, for the log
     * @param description one line on what you share, shown in {@code GET /extensions}
     */
    protected Expansion(String id, String name, String description) {
        this.id = Names.check(Names.ID, id, "expansion id");
        this.name = name == null || name.isBlank() ? id : name;
        this.description = Names.text(description);
    }

    public final String id() {
        return id;
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    /**
     * Shares the expansion, or replaces the one with its id. It stays registered until {@link #unregister()}: a WorldAPI
     * that starts later gets it too. A broken expansion (an id another plugin owns, a feature this WorldAPI doesn't have
     * yet) is refused with a line in the server's log.
     */
    public final void register() {
        Registry.register(this);
    }

    /** Takes the expansion out, for a plugin that's being disabled. WorldAPI does it by itself when the plugin stops. */
    public final void unregister() {
        Registry.unregister(id);
    }

    /** Whether WorldAPI runs and takes expansions now. */
    public static boolean available() {
        return Registry.available();
    }

    /** Every parameter it declared. */
    public final List<Param<?>> params() {
        return List.copyOf(params);
    }

    /** Every resource it declared, in order. */
    public final List<Resource> resources() {
        return List.copyOf(resources);
    }

    /** Every action it declared, in order. Since API 2.1. */
    public final List<Action> actions() {
        return List.copyOf(actions);
    }

    /** Every event it declared, in order. */
    public final List<Event<?>> events() {
        return List.copyOf(events);
    }

    /**
     * An event with a record for its data, like {@code boss.killed}: lower-case words of letters, digits, and underscores,
     * joined by dots. Publish it through the returned field.
     */
    protected final <T extends Record> Event<T> event(String name, Class<T> payload, String description) {
        checkOpen();
        if (events.stream().anyMatch(it -> it.name().equals(name))) throw new IllegalArgumentException(id + " declares the event " + name + " twice");
        Event<T> event = new Event<>(this, name, payload, description);
        events.add(event);
        return event;
    }

    /**
     * A resource only clients with a token read, until {@link Resource#publicly()}. Each path segment is lower-case
     * letters, digits, underscores, and hyphens, or a parameter in braces, like {@code {player}}, declared above it.
     */
    protected final Resource resource(String path, String description, Resource.Handler handler) {
        checkOpen();
        String trimmed = trim(path);
        if (resources.stream().anyMatch(it -> it.path().equals(trimmed))) throw new IllegalArgumentException(id + " declares " + trimmed + " twice");
        Resource resource = new Resource(this, trimmed, description, handler, pathParams(trimmed));
        resources.add(resource);
        return resource;
    }

    /**
     * An action clients call with {@code POST /extensions/<id>/<path>} and a JSON body: something your plugin does on
     * request, like spawning a boss. Name its {@link Action#permission}. Since API 2.1.
     */
    protected final Action action(String path, String description, Resource.Handler handler) {
        return add(Action.Method.POST, path, description, handler);
    }

    /**
     * An action clients call with {@code DELETE /extensions/<id>/<path>}: something your plugin takes away or ends on
     * request, like a player's AFK. Name its {@link Action#permission}. Since API 2.1.
     */
    protected final Action remove(String path, String description, Resource.Handler handler) {
        return add(Action.Method.DELETE, path, description, handler);
    }

    private Action add(Action.Method method, String path, String description, Resource.Handler handler) {
        checkOpen();
        String trimmed = trim(path);
        if (actions.stream().anyMatch(it -> it.method() == method && it.path().equals(trimmed))) throw new IllegalArgumentException(id + " declares " + method + " " + trimmed + " twice");
        Action action = new Action(this, method, trimmed, description, handler, pathParams(trimmed));
        actions.add(action);
        return action;
    }

    private static String trim(String path) {
        return Names.check(Names.PATH, path, "path").replaceAll("^/|/$", "");
    }

    private List<Param<?>> pathParams(String path) {
        return Names.pathParams(path).stream().map(param -> params.stream()
            .filter(it -> it.name().equals(param))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Declare the parameter " + param + " before " + path)))
            .<Param<?>>map(it -> it)
            .toList();
    }

    /** A whole number, {@code fallback} when it's missing, kept between {@code min} and {@code max}. */
    protected final Param<Integer> integer(String name, String description, int min, int max, int fallback) {
        if (min > max || fallback < min || fallback > max) throw new IllegalArgumentException(name + " needs min ≤ fallback ≤ max");
        return param(new Param<>(this, name, description, Param.Kind.INTEGER, Integer.class, fallback, min, max, List.of()));
    }

    /** Any text, null when it's missing. */
    protected final Param<String> text(String name, String description) {
        return param(new Param<>(this, name, description, Param.Kind.TEXT, String.class, null, 0, 0, List.of()));
    }

    /** True for {@code ?name}, {@code true}, {@code 1}, or {@code yes}, false when it's missing. */
    protected final Param<Boolean> flag(String name, String description) {
        return param(new Param<>(this, name, description, Param.Kind.FLAG, Boolean.class, false, 0, 0, List.of()));
    }

    /** One of an enum's constants by name, in any case, {@code fallback} when it's missing. */
    protected final <E extends Enum<E>> Param<E> choice(String name, String description, Class<E> type, E fallback) {
        List<String> choices = Arrays.stream(type.getEnumConstants()).map(it -> it.name().toLowerCase(Locale.ROOT)).toList();
        return param(new Param<>(this, name, description, Param.Kind.CHOICE, type, fallback, 0, 0, choices));
    }

    /**
     * A player by name or UUID, online or not, null when it's missing. One WorldAPI doesn't know answers 404. Vanished
     * players are found too, as they are when offline, so it never tells who's online.
     */
    protected final Param<PlayerRef> player(String name, String description) {
        return param(new Param<>(this, name, description, Param.Kind.PLAYER, PlayerRef.class, null, 0, 0, List.of()));
    }

    /**
     * A place in an action's body, sent as {@code {"world": "world", "x": 0, "y": 64, "z": 0}}, null when it's missing.
     * Since API 2.1.
     */
    protected final Param<Place> place(String name, String description) {
        return param(new Param<>(this, name, description, Param.Kind.PLACE, Place.class, null, 0, 0, List.of()));
    }

    private <T> Param<T> param(Param<T> param) {
        checkOpen();
        if (params.stream().anyMatch(it -> it.name().equals(param.name()))) throw new IllegalArgumentException(id + " declares the parameter " + param.name() + " twice");
        params.add(param);
        return param;
    }

    void freeze() {
        frozen = true;
    }

    void checkOpen() {
        if (frozen) throw new IllegalStateException(id + " is registered: declare everything while it's built");
    }
}
