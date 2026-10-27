package com.hop.drop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.BidiFormatter;
import android.text.format.Formatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An explicit app language: new installs start in English on every phone. */
public final class Language {
    private Language() { }

    public static String selected(Context context) {
        SharedPreferences settings = context.getSharedPreferences("settings_v2", Context.MODE_PRIVATE);
        String value = settings.getString("language", null);
        if ("ar".equals(value)) return "ar";
        if (!"en".equals(value)) settings.edit().putString("language", "en").apply();
        return "en";
    }

    public static Context wrap(Context context) {
        Locale locale = Locale.forLanguageTag(selected(context));
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }

    public static String text(Context context, int id, Object... args) {
        Object[] formatted = args.clone();
        for (int i = 0; i < formatted.length; i++)
            if (formatted[i] instanceof String) formatted[i] = ltr(context, (String) formatted[i]);
        return wrap(context).getString(id, formatted);
    }

    public static String files(Context context, int count) {
        Context localized = wrap(context);
        return localized.getResources().getQuantityString(R.plurals.file_count, count, count);
    }

    public static String fileObjects(Context context, int count) {
        Context localized = wrap(context);
        return localized.getResources().getQuantityString(R.plurals.file_object_count, count, count);
    }

    public static String folders(Context context, int count) {
        Context localized = wrap(context);
        return localized.getResources().getQuantityString(R.plurals.folder_count, count, count);
    }

    public static String size(Context context, long bytes) {
        Context localized = wrap(context);
        return bytes < 0 ? localized.getString(R.string.unknown_size) : Formatter.formatShortFileSize(localized, bytes);
    }

    public static String duration(Context context, long seconds) {
        if (seconds < 0) return "";
        Context localized = wrap(context);
        long value = Math.max(1, seconds);
        if (value < 60) return localized.getResources().getQuantityString(R.plurals.second_count, (int)value, value);
        long minutes = value / 60;
        if (minutes < 10) return localized.getResources().getQuantityString(R.plurals.minute_count, (int)minutes, minutes)
                + " " + localized.getResources().getQuantityString(R.plurals.second_count, (int)(value % 60), value % 60);
        if (minutes < 60) return localized.getResources().getQuantityString(R.plurals.minute_count, (int)minutes, minutes);
        long hours = minutes / 60;
        return localized.getResources().getQuantityString(R.plurals.hour_count, (int)hours, hours)
                + " " + localized.getResources().getQuantityString(R.plurals.minute_count, (int)(minutes % 60), minutes % 60);
    }

    /** Isolate a filename, path, IP address, or other LTR identifier inside an Arabic sentence. */
    public static String ltr(Context context, String value) {
        return BidiFormatter.getInstance("ar".equals(selected(context))).unicodeWrap(value);
    }

    private static List<String> top(Context context, List<String> names) {
        List<String> result = new ArrayList<>();
        Set<String> folders = new HashSet<>();
        for (String name : names) {
            int slash = name.indexOf('/');
            if (slash <= 0) result.add(ltr(context, name));
            else if (folders.add(name.substring(0, slash)))
                result.add(text(context, R.string.folder_named, ltr(context, name.substring(0, slash))));
        }
        return result;
    }

    public static String names(Context context, List<String> names) {
        List<String> shown = top(context, names);
        if (shown.isEmpty()) return text(context, R.string.ui_no_files);
        if (shown.size() == 1) return shown.get(0);
        if (shown.size() == 2) return text(context, R.string.selection_and, shown.get(0), shown.get(1));
        return text(context, R.string.summary_more, shown.get(0), text(context, R.string.more_count, shown.size() - 1));
    }

    public static String list(Context context, List<String> names, int max) {
        List<String> shown = top(context, names);
        if (shown.isEmpty()) return text(context, R.string.ui_no_files);
        if (shown.size() <= max) return String.join("\n", shown);
        return String.join("\n", shown.subList(0, max)) + "\n+ " + text(context, R.string.more_count, shown.size() - max);
    }
}
