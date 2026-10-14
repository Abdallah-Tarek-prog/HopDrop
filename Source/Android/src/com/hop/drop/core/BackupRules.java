package com.hop.drop.core;

import java.util.Locale;
import java.util.Map;

/** Pure rules shared by the backup scheduler and the desktop JVM tests. */
public final class BackupRules {
    public static final int PHOTOS = 1, VIDEOS = 2, DOCUMENTS = 4, AUDIO = 8, ALL = 15;
    private BackupRules() { }

    public static boolean kind(int selected, String mime, String name) {
        if (selected == ALL) return true;
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String ext = name == null || !name.contains(".") ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        int kind;
        if (type.startsWith("image/") || has("jpg jpeg png gif heic heif webp raw dng bmp avif", ext)) kind = PHOTOS;
        else if (type.startsWith("video/") || has("mp4 mov avi mkv webm 3gp m4v", ext)) kind = VIDEOS;
        else if (type.startsWith("audio/") || has("mp3 m4a wav flac ogg aac opus wma", ext)) kind = AUDIO;
        else if (type.startsWith("text/") || type.equals("application/pdf") || type.contains("document") || type.contains("spreadsheet") || type.contains("presentation")
                || has("pdf txt doc docx xls xlsx ppt pptx odt ods odp rtf csv md epub", ext)) kind = DOCUMENTS;
        else kind = 0;
        return (selected & kind) != 0;
    }
    private static boolean has(String list, String ext) { return !ext.isEmpty() && (" " + list + " ").contains(" " + ext + " "); }

    /** Minutes since local midnight. Equal ends mean all day. */
    public static boolean inWindow(int minute, int from, int to) {
        if (from == to) return true;
        return from < to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    public static boolean allowed(boolean on, boolean chargerOnly, boolean plugged, boolean timed, int minute, int from, int to) {
        return on && (!chargerOnly || plugged) && (!timed || inWindow(minute, from, to));
    }

    /** Shape of the old single-backup settings when converted to the new list. */
    public static Map<String,Object> legacy(String id, boolean on, String tree, String folder, String device, boolean charging, String status, long lastRun) {
        return Json.obj("id", id, "on", on, "tree", tree, "folder", folder, "device", device, "kinds", ALL,
                "charging", charging, "timed", false, "from", 60, "to", 360, "status", status, "lastRun", lastRun, "lastCheck", 0);
    }
    public static String sentFile(String id, String device) {
        if ("legacy".equals(id)) return oldSentFile(device);
        return "backup-sent-" + id + "-" + device.substring(0, Math.min(16, device.length())) + ".txt";
    }
    public static String oldSentFile(String device) {
        return "backup-sent-" + device.substring(0, Math.min(16, device.length())) + ".txt";
    }
}
