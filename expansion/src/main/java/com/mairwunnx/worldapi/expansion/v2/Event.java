package com.mairwunnx.worldapi.expansion.v2;

/**
 * One kind of event an expansion publishes, like {@code boss.killed}, with a record for its data. Clients subscribe to
 * it as the topic {@code ext.<id>.<name>}. Declare it as a field of the {@link Expansion} and publish through that
 * field, so only events the expansion declared can go out, and only with their own data.
 *
 * <p>The record's components are the event's data, written as JSON with their names. A {@link PlayerRef} among them
 * hides the event while the player is vanished, and every {@link Place} is left out for clients that mustn't see
 * coordinates.
 */
public final class Event<T extends Record> {
    private final Expansion expansion;
    private final String name;
    private final Class<T> payload;
    private final String description;
    private boolean isPublic;
    private boolean recorded;

    Event(Expansion expansion, String name, Class<T> payload, String description) {
        if (payload == null) throw new IllegalArgumentException("The event " + name + " needs a record for its data");
        this.expansion = expansion;
        this.name = Names.check(Names.EVENT, name, "event name");
        this.payload = payload;
        this.description = Names.text(description);
    }

    /**
     * Visitors without a token may subscribe too, unless the server's owner hides it. WorldAPI still leaves out every
     * {@link Place} and hides events about vanished players.
     */
    public Event<T> publicly() {
        expansion.checkOpen();
        isPublic = true;
        return this;
    }

    /** WorldAPI keeps these in its history, so {@code GET /events} and the MCP server find them later. */
    public Event<T> recorded() {
        expansion.checkOpen();
        recorded = true;
        return this;
    }

    /**
     * Sends one event to WorldAPI's clients and, when recorded, to the history. Safe on any thread. Returns false, and
     * drops the event, while WorldAPI isn't running or the expansion isn't registered.
     */
    public boolean publish(T data) {
        return Registry.publish(this, data);
    }

    public Expansion expansion() {
        return expansion;
    }

    public String name() {
        return name;
    }

    /** The record that holds the event's data. */
    public Class<T> payload() {
        return payload;
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
