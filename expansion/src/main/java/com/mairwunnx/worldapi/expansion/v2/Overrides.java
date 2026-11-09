package com.mairwunnx.worldapi.expansion.v2;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What one resource or action says about its parameters beyond their declarations: required, a fallback, a range. */
final class Overrides {
    private final Set<Param<?>> required = new HashSet<>();
    private final Map<Param<?>, Object> fallbacks = new HashMap<>();
    private final Map<Param<?>, long[]> ranges = new HashMap<>();

    void required(String owner, List<Param<?>> params, Param<?>... which) {
        for (Param<?> param : which) required.add(check(owner, params, param));
    }

    <T> void fallback(String owner, List<Param<?>> params, Param<T> param, T fallback) {
        fallbacks.put(check(owner, params, param), fallback);
    }

    void range(String owner, List<Param<?>> params, Param<Integer> param, int min, int max) {
        check(owner, params, param);
        if (param.kind() != Param.Kind.INTEGER) throw new IllegalArgumentException(param.name() + " isn't a whole number");
        if (min > max) throw new IllegalArgumentException(param.name() + " needs min ≤ max");
        ranges.put(param, new long[] {min, max});
    }

    boolean requires(Param<?> param) {
        return param.isRequired() || required.contains(param);
    }

    Object fallbackOf(Param<?> param) {
        return fallbacks.containsKey(param) ? fallbacks.get(param) : param.fallback();
    }

    long minOf(Param<?> param) {
        return ranges.containsKey(param) ? ranges.get(param)[0] : param.min();
    }

    long maxOf(Param<?> param) {
        return ranges.containsKey(param) ? ranges.get(param)[1] : param.max();
    }

    <T> T parse(Param<T> param, String raw) {
        return param.parse(raw, requires(param), param.type().cast(fallbackOf(param)), minOf(param), maxOf(param));
    }

    private static Param<?> check(String owner, List<Param<?>> params, Param<?> param) {
        if (param == null || !params.contains(param)) throw new IllegalArgumentException(owner + " doesn't read " + (param == null ? "null" : param.name()) + ": list it first");
        return param;
    }
}
