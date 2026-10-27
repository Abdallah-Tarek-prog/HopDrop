using System.Globalization;
using System.Resources;
using System.Windows;
using System.Windows.Markup;

namespace HopDrop.Desktop;

internal static class L
{
    private static readonly ResourceManager Resources = new("HopDrop.Desktop.Resources.Strings", typeof(L).Assembly);
    public static CultureInfo Culture { get; private set; } = CultureInfo.GetCultureInfo("en");
    public static bool Arabic => Culture.TwoLetterISOLanguageName == "ar";
    public static FlowDirection Direction => Arabic ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
    public static XmlLanguage Language => XmlLanguage.GetLanguage(Arabic ? "ar" : "en");

    public static void Apply(string? language)
    {
        var culture = CultureInfo.GetCultureInfo(language == "ar" ? "ar" : "en");
        Culture = culture;
        CultureInfo.DefaultThreadCurrentCulture = CultureInfo.CurrentCulture = culture;
        CultureInfo.DefaultThreadCurrentUICulture = CultureInfo.CurrentUICulture = culture;
    }

    public static string T(string key, params object?[] arguments)
    {
        string text = Resources.GetString(key, Culture) ?? throw new MissingManifestResourceException(key);
        if (Arabic)
            arguments = arguments.Select(value => value is string data ? (object)("\u200E" + data + "\u200E") : value).ToArray();
        return arguments.Length == 0 ? text : string.Format(Culture, text, arguments);
    }

    public static string Count(string noun, int count)
    {
        string form = count == 0 ? "Zero" : count == 1 ? "One" : count == 2 ? "Two" :
            count % 100 is >= 3 and <= 10 ? "Few" : count % 100 is >= 11 and <= 99 ? "Many" : "Other";
        if (!Arabic) form = count == 1 ? "One" : "Other";
        return T(noun + form, count);
    }

    public static string Transport(string? transport) => T(transport switch
    {
        Core.TransferTransport.LocalNetwork => "TransportLocalNetwork",
        Core.TransferTransport.DirectLink => "TransportDirectLink",
        Core.TransferTransport.Bluetooth => "TransportBluetooth",
        Core.TransferTransport.Mixed => "TransportMixed",
        _ => "TransportUnknown"
    });
}
