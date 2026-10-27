using System.Globalization;
using System.Resources;
using HopDrop.Core;

namespace HopDrop.Linux;

internal sealed record LinuxPreferences(string Language = "en", bool CloseToTray = false, string Theme = "system",
    bool StartWithSession = false, bool BackgroundReceive = false, bool PutReceivedTextOnClipboard = true,
    bool ShareCopiedText = false, bool Agents = false, bool FileManagerIntegration = false);

internal static class L
{
    private static readonly ResourceManager Strings = new("HopDrop.Linux.Resources.Strings", typeof(L).Assembly);
    public static string Language { get; set; } = "en";
    public static CultureInfo Culture => CultureInfo.GetCultureInfo(Language == "ar" ? "ar" : "en");

    public static string Get(string key, params object[] args)
    {
        string value = Strings.GetString(key, Culture) ?? throw new MissingManifestResourceException(key);
        if (Language == "ar")
            args = args.Select(arg => arg is string text ? (object)("\u2068" + text + "\u2069") : arg).ToArray();
        return args.Length == 0 ? value : string.Format(Culture, value, args);
    }

    public static string Files(int count)
    {
        string key = count == 1 ? "file_one" : "file_other";
        if (Language == "ar")
            key = count == 0 ? "file_zero" : count == 1 ? "file_one" : count == 2 ? "file_two" :
                count % 100 is >= 3 and <= 10 ? "file_few" : count % 100 is >= 11 and <= 99 ? "file_many" : "file_other";
        return Get(key, count);
    }

    public static string Error(Exception error) => error is HopDropException hop ? Error(hop.Code) : Get(error switch
    {
        System.Net.Sockets.SocketException or TimeoutException => "error_network",
        System.Security.Cryptography.CryptographicException => "error_identity_storage",
        IOException or UnauthorizedAccessException => "error_file",
        _ => "error_unknown"
    });
    public static string Error(string code) => Get(code switch
    {
        "not_paired" => "error_not_paired", "identity_mismatch" => "error_identity",
        "declined" => "error_declined", "no_space" => "error_space", "cancelled" => "error_cancelled",
        "unsupported_version" or "protocol_error" => "error_protocol",
        "timeout" or "disconnected" => "error_network", "io_error" => "error_file",
        _ => "error_unknown"
    });
}
