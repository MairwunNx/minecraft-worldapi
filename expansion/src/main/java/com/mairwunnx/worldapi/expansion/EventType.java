package com.mairwunnx.worldapi.expansion;

/**
 * One kind of event an expansion publishes, like {@code boss.killed}. Clients subscribe to it as the topic
 * {@code ext.<id>.<name>}. Immutable: {@link #publicly()} and {@link #recorded()} return changed copies.
 */
public final class EventType {
    private final String name;
    private final String description;
    private final boolean isPublic;
    private final boolean recorded;

    private EventType(String name, String description, boolean isPublic, boolean recorded) {
        this.name = name;
        this.description = description;
        this.isPublic = isPublic;
        this.recorded = recorded;
    }

    /** Lower-case words of letters, digits, and underscores, joined by dots, like {@code player.level_up}. */
    public static EventType of(String name, String description) {
        return new EventType(Names.check(Names.EVENT, name, "event name"), Names.text(description), false, false);
    }

    /**
     * Visitors without a token may subscribe too, unless the server's owner hides it. WorldAPI still strips what its
     * privacy settings hide from them, such as the place under {@code location} in the data.
     */
    public EventType publicly() {
        return new EventType(name, description, true, recorded);
    }

    /** WorldAPI keeps these in its history, so {@code GET /events} and the MCP server find them later. */
    public EventType recorded() {
        return new EventType(name, description, isPublic, true);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean isRecorded() {
        return recorded;
    }
}
