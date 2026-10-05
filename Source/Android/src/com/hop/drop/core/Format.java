package com.hop.drop.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Human-readable sizes, speeds, durations and file lists, shared by screens and notifications. */
public final class Format {
    private Format() {
    }

    public static String size(long bytes) {
        if (bytes < 0) return "Unknown size";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    public static String speed(double bytesPerSecond) {
        return size((long) bytesPerSecond) + "/s";
    }

    /** "45 s", "2 min 10 s", "12 min", "1 h 5 min". */
    public static String duration(long seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return Math.max(1, seconds) + " s";
        long minutes = seconds / 60;
        if (minutes < 10) return minutes + " min " + seconds % 60 + " s";
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** "1 file", "1,240 files". */
    public static String files(int count) {
        return String.format(Locale.ROOT, "%,d", count) + (count == 1 ? " file" : " files");
    }

    /** "1 folder", "2 folders". */
    public static String folders(int count) {
        return String.format(Locale.ROOT, "%,d", count) + (count == 1 ? " folder" : " folders");
    }

    /** "a.jpg, b.pdf +3" — at most two names. Files that came inside a sent folder count once, as "Photos folder". */
    public static String names(List<String> names) {
        return list(names, 2);
    }

    /**
     * At most {@code max} entries, then "+N". Paths inside a sent folder ("Photos/2024/a.jpg") are shown as their
     * top folder ("Photos folder"), once.
     */
    public static String list(List<String> names, int max) {
        List<String> shown = new ArrayList<>();
        Set<String> folders = new HashSet<>();
        for (String name : names) {
            int slash = name.indexOf('/');
            if (slash <= 0) shown.add(name);
            else if (folders.add(name.substring(0, slash))) shown.add(name.substring(0, slash) + " folder");
        }
        if (shown.isEmpty()) return "No files";
        if (shown.size() <= max) return String.join(", ", shown);
        return String.join(", ", shown.subList(0, max)) + " +" + (shown.size() - max);
    }

    /** "412.0 MB of 1.60 GB" or just "412.0 MB" when the total is unknown. */
    public static String amount(long done, long total) {
        return total < 0 ? size(done) : size(done) + " of " + size(total);
    }
}
