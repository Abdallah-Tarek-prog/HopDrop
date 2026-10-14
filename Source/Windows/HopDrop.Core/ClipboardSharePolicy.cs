namespace HopDrop.Core;

public static class ClipboardSharePolicy
{
    public static bool IsPrivate(bool exclude, bool viewerIgnore, int? history, int? cloud) =>
        exclude || viewerIgnore || history == 0 || cloud == 0;

    public static bool ShouldShare(bool enabled, string? text, string? own, string? last, bool privateContent) =>
        enabled && !privateContent && text is { Length: > 0 and <= HopDropPeer.MaxText } && text != own && text != last;
}
