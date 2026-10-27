package com.hop.drop.core;

/** The methods actually used by one transfer, including a resumed connection. */
public final class Transport {
    private Transport() { }

    public static String stored(String value) {
        return "local_network".equals(value) || "direct_link".equals(value)
                || "bluetooth".equals(value) || "mixed".equals(value) ? value : "unknown";
    }

    public static String combine(String first, String next) {
        if (first == null || next == null || "unknown".equals(first) || "unknown".equals(next)) return "unknown";
        return first.equals(next) ? first : "mixed";
    }
}
