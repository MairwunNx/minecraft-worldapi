package com.mairwunnx.worldapi.expansion.v2;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A parameter of a path, a query, or an action's body, declared once on the {@link Expansion} and read with
 * {@link Request#get}. A path takes it by name, like {@code {player}}, a query through {@link Resource#query} or
 * {@link Action#query}, and a body through {@link Action#body}. Its kind, range, and description go into
 * {@code GET /extensions}, so clients and the MCP server learn what a resource or an action accepts.
 */
public final class Param<T> {
    /** How WorldAPI reads the text a client sends. */
    public enum Kind { INTEGER, TEXT, FLAG, CHOICE, PLAYER, PLACE }

    private final Expansion expansion;
    private final String name;
    private final String description;
    private final Kind kind;
    private final Class<T> type;
    private final T fallback;
    private final long min;
    private final long max;
    private final List<String> choices;
    private boolean required;

    Param(Expansion expansion, String name, String description, Kind kind, Class<T> type, T fallback, long min, long max, List<String> choices) {
        this.expansion = expansion;
        this.name = Names.check(Names.PARAM, name, "parameter name");
        this.description = Names.text(description);
        this.kind = kind;
        this.type = type;
        this.fallback = fallback;
        this.min = min;
        this.max = max;
        this.choices = choices;
    }

    /**
     * The client must send it: a request without it answers 400. A path parameter is always there. Since API 2.1.
     */
    public Param<T> required() {
        expansion.checkOpen();
        required = true;
        return this;
    }

    /** Whether a request without it answers 400. */
    public boolean isRequired() {
        return required;
    }

    /**
     * Reads a client's value: {@link #fallback()} when it's missing. A whole number outside the range is kept inside it.
     * Throws {@link IllegalArgumentException}, which answers 400, for a value that doesn't fit. WorldAPI reads players
     * itself, since only the server knows them.
     */
    public T parse(String raw) {
        if (raw == null || raw.isEmpty() && kind != Kind.FLAG) {
            if (required) throw new IllegalArgumentException(name + " is required");
            return fallback;
        }
        String value = raw.trim();
        return type.cast(switch (kind) {
            case INTEGER -> {
                long number;
                try {
                    number = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + " must be a whole number");
                }
                yield (int) Math.max(min, Math.min(max, number));
            }
            case TEXT -> raw;
            case FLAG -> switch (value.toLowerCase(Locale.ROOT)) {
                case "", "true", "1", "yes" -> true;
                case "false", "0", "no" -> false;
                default -> throw new IllegalArgumentException(name + " must be true or false");
            };
            case CHOICE -> Arrays.stream(type.getEnumConstants())
                .filter(it -> ((Enum<?>) it).name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " must be one of " + String.join(", ", choices)));
            case PLAYER -> throw new UnsupportedOperationException("WorldAPI reads players");
            case PLACE -> throw new UnsupportedOperationException("WorldAPI reads places");
        });
    }

    public Expansion expansion() {
        return expansion;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Kind kind() {
        return kind;
    }

    public Class<T> type() {
        return type;
    }

    /** What a missing value reads as: null for text, players, places, and choices without one, false for a flag. */
    public T fallback() {
        return fallback;
    }

    /** The smallest whole number, for {@link Kind#INTEGER}. */
    public long min() {
        return min;
    }

    /** The largest whole number, for {@link Kind#INTEGER}. */
    public long max() {
        return max;
    }

    /** The values a {@link Kind#CHOICE} takes, in lower case. */
    public List<String> choices() {
        return choices;
    }
}
