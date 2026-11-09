package com.mairwunnx.worldapi.expansion.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shapes of ids, paths, event names, and parameters, the same ones WorldAPI checks on its side. */
final class Names {
    static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    static final Pattern EVENT = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");
    static final Pattern PARAM = Pattern.compile("[a-z0-9_]{1,32}");
    static final Pattern PATH = Pattern.compile("/?([a-z0-9_-]+|\\{[a-z0-9_]+})(/([a-z0-9_-]+|\\{[a-z0-9_]+}))*/?");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([a-z0-9_]+)}");

    private Names() {}

    static String check(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) throw new IllegalArgumentException("Bad " + what + ": " + value);
        return value;
    }

    static String text(String value) {
        return value == null ? "" : value;
    }

    /** The parameters in a path, like {@code player} in {@code players/{player}}, in order. */
    static List<String> pathParams(String path) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATH_PARAM.matcher(path);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }
}
