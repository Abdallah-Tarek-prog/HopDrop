namespace HopDrop.Core;

public static class FileNames
{
    private static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
    { "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
      "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9" };

    public static string Sanitize(string? input)
    {
        string segment = (input ?? "").Split(['/', '\\']).Last();
        if (segment is "" or "." or "..") return "file";
        char[] chars = segment.Select(c => c < 0x20 || "<>:\"/\\|?*".Contains(c) ? '_' : c).ToArray();
        string name = new string(chars).TrimEnd('.', ' ');
        if (name.Length == 0) return "file";
        if (Reserved.Contains(name.Split('.')[0])) name = "_" + name;
        if (name.Length <= 180) return name;
        var (stem, extension) = SplitExtension(name);
        if (extension.Length >= 180) return SafePrefix(name, 180);
        int limit = Math.Max(0, 180 - extension.Length);
        return SafePrefix(stem, limit) + extension;
    }
    public static string Unique(string input, Func<string, bool> exists)
    {
        string name = Sanitize(input);
        if (!exists(name)) return name;
        var (stem, extension) = SplitExtension(name);
        for (int n = 1; ; n++)
        {
            string suffix = $" ({n})";
            string keptExtension = extension.Length + suffix.Length > 180 ? SafePrefix(extension, 180 - suffix.Length) : extension;
            int limit = Math.Max(0, 180 - keptExtension.Length - suffix.Length);
            string result = SafePrefix(stem, limit) + suffix + keptExtension;
            if (!exists(result)) return result;
        }
    }
    public static (string Stem, string Extension) SplitExtension(string name)
    {
        int dot = name.LastIndexOf('.');
        return dot > 0 ? (name[..dot], name[dot..]) : (name, "");
    }
    private static string SafePrefix(string value, int limit)
    {
        int length = Math.Min(value.Length, limit);
        if (length > 0 && length < value.Length && char.IsHighSurrogate(value[length - 1])) length--;
        return value[..length];
    }
}
