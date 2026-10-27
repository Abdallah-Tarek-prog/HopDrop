using System.Net;
using System.Text.Json;
using HopDrop.Core;
using Xunit;

namespace HopDrop.Tests;

public class TransportTests
{
    [Fact]
    public void DirectLinkRequiresTheConnectionToUseItsAddress()
    {
        var link = new DirectLinkInfo("DIRECT-HopDrop-Test", "test-password", "192.168.137.1");
        Assert.Equal(TransferTransport.LocalNetwork, TransferTransport.ForConnection(IPAddress.Parse("192.168.1.5"), IPAddress.Parse("192.168.1.8"), link, null));
        Assert.Equal(TransferTransport.DirectLink, TransferTransport.ForConnection(IPAddress.Parse("192.168.137.1"), IPAddress.Parse("192.168.137.2"), link, null));
        Assert.Equal(TransferTransport.DirectLink, TransferTransport.ForConnection(IPAddress.Parse("192.168.137.2"), IPAddress.Parse("192.168.137.1"), null, link));
        Assert.Equal(TransferTransport.Mixed, TransferTransport.Combine(TransferTransport.LocalNetwork, TransferTransport.DirectLink));
    }

    [Fact]
    public void OldHistoryDoesNotAcquireAnInventedTransport()
    {
        var old = JsonSerializer.Deserialize<HistoryEntry>("""{"At":"2026-10-01T12:00:00Z","PeerId":"old-device","Direction":"received","Result":"ok","Files":["photo.jpg"],"Folder":"Downloads"}""");
        Assert.NotNull(old);
        Assert.Null(old.Transport);
        Assert.Equal(1, old.FileCount);
    }

    [Fact]
    public async Task RealFileTextAndBluetoothHistoryKeepTheirTransport()
    {
        string root = Scratch.NewFolder();
        static PeerOptions Options(string path) => new(new HopDropPaths(Path.Combine(path, "data"), Path.Combine(path, "logs"), Path.Combine(path, "received")), 0, "Test");
        await using var receiver = await HopDropPeer.OpenAsync(Options(Path.Combine(root, "receiver")));
        await using var sender = await HopDropPeer.OpenAsync(Options(Path.Combine(root, "sender")));
        await receiver.StartAsync();
        await sender.PairQrAsync(receiver.CreateQrUri([IPAddress.Loopback]));
        string file = Path.Combine(root, "photo.txt");
        await File.WriteAllTextAsync(file, "اختبار النقل");
        await sender.SendAsync(receiver.Id, [file]);
        await sender.SendTextAsync(receiver.Id, "مرحبا بالعالم");
        // Text acknowledgement precedes recording on the receiver.
        for (int attempt = 0; attempt < 50 && (await receiver.HistoryAsync()).Count < 2; attempt++) await Task.Delay(20);
        Assert.All(await sender.HistoryAsync(), entry => Assert.Equal(TransferTransport.LocalNetwork, entry.Transport));
        var incoming = await receiver.HistoryAsync();
        Assert.Equal(2, incoming.Count);
        Assert.All(incoming, entry => Assert.Equal(TransferTransport.LocalNetwork, entry.Transport));
        Assert.Contains(incoming, entry => entry.Text == "مرحبا بالعالم");
        await receiver.RecordBluetoothAsync([file]);
        Assert.Equal(TransferTransport.Bluetooth, (await receiver.HistoryAsync())[^1].Transport);
    }
}
