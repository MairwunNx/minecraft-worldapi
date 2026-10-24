package com.mairwunnx.worldapi.expansion;

import java.util.regex.Pattern;

/** The shapes of ids, paths, and event names, the same ones WorldAPI checks on its side. */
final class Names {
    static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    static final Pattern EVENT = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");
    static final Pattern PATH = Pattern.compile("/?([a-z0-9_-]+|\\{[a-z0-9_]+})(/([a-z0-9_-]+|\\{[a-z0-9_]+}))*/?");

    private Names() {}

    static String check(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) throw new IllegalArgumentException("Bad " + what + ": " + value);
        return value;
    }

    static String text(String value) {
        return value == null ? "" : value;
    }
}
