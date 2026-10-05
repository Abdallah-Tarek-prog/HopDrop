package com.hop.drop.core;

import java.util.Locale;
import java.util.function.Predicate;

public final class FileNames {
    private FileNames() { }
    private static String prefix(String s, int limit) {
        int n = Math.min(s.length(), limit);
        if (n > 0 && n < s.length() && Character.isHighSurrogate(s.charAt(n-1))) n--;
        return s.substring(0,n);
    }
    public static String sanitize(String input) {
        String name = input == null ? "" : input.substring(Math.max(input.lastIndexOf('/'), input.lastIndexOf('\\')) + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "file";
        StringBuilder b = new StringBuilder();
        for (char c : name.toCharArray()) b.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        name = b.toString().replaceAll("[. ]+$", "");
        if (name.isEmpty()) return "file";
        String head = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (head.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) name = "_" + name;
        if (name.length() <= 180) return name;
        int dot = name.lastIndexOf('.'); String ext = dot > 0 ? name.substring(dot) : "";
        if (ext.length() >= 180) return prefix(name,180);
        return prefix(name.substring(0,name.length()-ext.length()),180-ext.length()) + ext;
    }
    public static String unique(String input, Predicate<String> exists) {
        String name = sanitize(input); if (!exists.test(name)) return name;
        int dot = name.lastIndexOf('.'); String ext = dot > 0 ? name.substring(dot) : "";
        String stem = name.substring(0,name.length()-ext.length());
        for (int i=1;;i++) { String suffix = " (" + i + ")"; String e = ext.length()+suffix.length()>180 ? prefix(ext,180-suffix.length()) : ext;
            String candidate = prefix(stem,180-e.length()-suffix.length()) + suffix + e;
            if (!exists.test(candidate)) return candidate;
        }
    }
}
