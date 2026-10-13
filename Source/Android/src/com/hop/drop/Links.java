package com.hop.drop;

import java.util.regex.Pattern;

/** Recognises text that is just one web link, so it can be offered as "Open link". */
final class Links {
    private static final Pattern URL = Pattern.compile("(?i)^https?://\\S+$");

    private Links() {
    }

    /** The link when the whole text (trimmed) is one http(s) link, else null. */
    static String single(String text) {
        if (text == null) return null;
        String t = text.trim();
        return t.length() <= 4000 && URL.matcher(t).matches() ? t : null;
    }
}
