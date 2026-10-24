package com.mairwunnx.worldapi.expansion;

import java.util.List;

/**
 * What a plugin or mod shares through WorldAPI: resources clients read at {@code /extensions/<id>/<path>}, and event
 * topics it publishes to, {@code ext.<id>.<event>}, which reach WebSocket clients and, when recorded, the history. The
 * MCP server reads them too, through its {@code list_extensions} and {@code read_extension} tools. Your plugin stays the
 * source of truth: WorldAPI stores none of its data, only the recorded events.
 *
 * <p>Write one class for every platform; register it with {@link WorldApiExpansions#register} or name it in
 * {@code META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion}. WorldAPI reads {@link #resources()} and
 * {@link #events()} once, when the expansion registers; register it again to change them.
 */
public interface WorldApiExpansion {
    /**
     * A short id of lower-case letters, digits, underscores, and hyphens, 1 to 32 long, like {@code mobosses}. It's the
     * namespace in {@code /extensions/mobosses/...} and in the topics {@code ext.mobosses.*}. One plugin owns an id: a
     * second plugin that takes it is refused.
     */
    String id();

    /** Return {@link ExpansionApi#VERSION}, so the version you compiled against is the one WorldAPI sees. */
    String requiresApi();

    /** The name people know your plugin or mod by, like {@code Mo’Bosses}, for the log; the id by default. */
    default String name() {
        return id();
    }

    /** One line on what you share, shown in {@code GET /extensions}. */
    default String description() {
        return "";
    }

    /** What clients may read. */
    default List<Resource> resources() {
        return List.of();
    }

    /** The events you publish with {@link WorldApiExpansions#publish}. An event not listed here is refused. */
    default List<EventType> events() {
        return List.of();
    }
}
