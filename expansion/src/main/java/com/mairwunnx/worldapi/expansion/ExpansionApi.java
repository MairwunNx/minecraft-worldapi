package com.mairwunnx.worldapi.expansion;

/**
 * The version of the expansion API. A 1.x expansion returns {@link #VERSION} from
 * {@link WorldApiExpansion#requiresApi()}: the compiler copies the constant into the expansion's own class, so WorldAPI
 * learns which version it was built against. Expansions of API 2 ({@link com.mairwunnx.worldapi.expansion.v2}) don't
 * name a version.
 *
 * <p>Within a major version the API only grows: new methods come with defaults, and nothing is removed. API 2 keeps
 * API 1's classes, so an expansion built against 1.0 runs on every WorldAPI with API 1.x or 2.x. WorldAPI refuses one
 * that needs a newer minor or another major, and says so in its log.
 */
public final class ExpansionApi {
    /** This API's version, {@code major.minor.patch}. */
    public static final String VERSION = "2.0.0";

    private ExpansionApi() {}

    /**
     * Whether an API of version {@code available} can run an expansion that asks for {@code required}: the same major,
     * and a minor at least as high. Patches never change what an expansion may call.
     */
    public static boolean satisfies(String available, String required) {
        int[] have = parse(available);
        int[] need = parse(required);
        if (have == null || need == null) return false;
        return have[0] == need[0] && have[1] >= need[1];
    }

    static int[] parse(String version) {
        if (version == null) return null;
        String[] parts = version.trim().split("\\.");
        if (parts.length < 2 || parts.length > 3) return null;
        int[] numbers = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
                if (numbers[i] < 0) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return numbers;
    }
}
