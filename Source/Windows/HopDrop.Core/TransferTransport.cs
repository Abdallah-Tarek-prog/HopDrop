using System.Net;

namespace HopDrop.Core;

/// <summary>Stable values saved in Activity; older entries have no recorded transport.</summary>
public static class TransferTransport
{
    public const string LocalNetwork = "local_network";
    public const string DirectLink = "direct_link";
    public const string Bluetooth = "bluetooth";
    public const string Mixed = "mixed";
    public const string Unknown = "unknown";

    internal static string ForConnection(IPAddress? local, IPAddress? remote, DirectLinkInfo? hostedLink, DirectLinkInfo? peerLink)
    {
        static bool Matches(IPAddress? address, DirectLinkInfo? link) =>
            address is not null && link is { Valid: true } && IPAddress.TryParse(link.Address, out var host) &&
            address.MapToIPv6().Equals(host.MapToIPv6());
        return Matches(local, hostedLink) || Matches(remote, peerLink) ? DirectLink : LocalNetwork;
    }

    internal static string Combine(string previous, string current) => previous == current ? current : Mixed;
}
