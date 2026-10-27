using System.Net;
using System.Security.Cryptography;
using HopDrop.Core;
using HopDrop.Linux;

string root = Path.Combine(Path.GetTempPath(), "hopdrop-desktop-tests-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
try
{
    static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    var integration = new DesktopIntegration(root, ["/opt/Hop Drop/a$`%\\\"pp", "--data", root]);
    Check(!DesktopIntegration.HostCommand(HopDropPaths.Default.DataDirectory).Contains("--data"), "default launch retains XDG settings");
    Check(DesktopIntegration.HostCommand(root).Contains("--data"), "custom launch keeps isolated profile");
    integration.SetAutostart(true, true); integration.SetFileManager(true);
    Check(File.ReadAllText(integration.AutostartPath).Contains("--background"), "background launch");
    Check(File.ReadAllText(integration.NemoPath).Contains("--send %F"), "Nemo selection arguments");
    Check(File.ReadAllText(integration.NautilusPath).Contains("\"$@\""), "Nautilus preserves selected filenames");
    Check(File.ReadAllText(integration.DolphinPath).Contains("Type=Service"), "Dolphin service");
    string unrelated = Path.Combine(Path.GetDirectoryName(integration.AutostartPath)!, "keep.desktop"); File.WriteAllText(unrelated, "keep");
    integration.SetAutostart(false, false); integration.SetFileManager(false);
    Check(File.Exists(unrelated) && !File.Exists(integration.AutostartPath) && !File.Exists(integration.NemoPath), "only own integration files removed");
    Console.WriteLine("PASS: isolated desktop integration");

    HopDropPaths Paths(string name) => new(Path.Combine(root, name), Path.Combine(root, name, "logs"), Path.Combine(root, name, "receive"));
    await using var sender = await HopDropPeer.OpenAsync(new PeerOptions(Paths("sender"), 0, "Linux queue"));
    await using var receiver = await HopDropPeer.OpenAsync(new PeerOptions(Paths("receiver"), 0, "Test receiver"));
    await receiver.StartAsync(); await sender.PairQrAsync(receiver.CreateQrUri([IPAddress.Loopback])); await sender.StartAsync();
    string source = Path.Combine(root, "test.bin"); byte[] bytes = RandomNumberGenerator.GetBytes(130_001); await File.WriteAllBytesAsync(source, bytes);
    await using (var queue = new OutgoingQueue(sender, Paths("sender").DataDirectory))
    {
        await queue.InitializeAsync();
        var result = await queue.SendFilesAsync(receiver.Id, SendItem.From([source]));
        Check(result.Status == "sent" && queue.Entries.Count == 0, "confirmed queue delivery");
        Check(File.ReadAllBytes(Path.Combine(receiver.ReceiveDirectory, "test.bin")).SequenceEqual(bytes), "queue bytes");
        if (!OperatingSystem.IsWindows())
        {
            string folder = Path.Combine(root, "case"); Directory.CreateDirectory(folder);
            await File.WriteAllTextAsync(Path.Combine(folder, "A.txt"), "upper"); await File.WriteAllTextAsync(Path.Combine(folder, "a.txt"), "lower");
            Check(SendItem.From([folder]).Count == 2, "case-sensitive file selection");
            await queue.SendFilesAsync(receiver.Id, SendItem.From([folder]));
            Check(File.ReadAllText(Path.Combine(receiver.ReceiveDirectory, "case", "A.txt")) == "upper" &&
                File.ReadAllText(Path.Combine(receiver.ReceiveDirectory, "case", "a.txt")) == "lower", "case-sensitive filenames and bytes preserved");
        }
        var text = await queue.SendTextAsync(receiver.Id, "اختبار عربي 123");
        Check(text.Status == "sent", "Arabic text delivery");
        // Persisted in-flight entries are not retried automatically after a restart.
        var entry = new QueuedSend(Guid.NewGuid(), receiver.Id, SendItem.From([source]), null, false, DateTimeOffset.UtcNow, true);
        var store = new JsonStore<List<QueuedSend>>(Path.Combine(Paths("sender").DataDirectory, "queue.json"), []);
        await store.UpdateAsync(_ => [entry]);
    }
    await using (var restarted = new OutgoingQueue(sender, Paths("sender").DataDirectory))
    {
        await restarted.InitializeAsync();
        var interrupted = restarted.Entries.Single(); Check(interrupted.Interrupted, "restart requires review");
        restarted.Retry(receiver.Id); await Task.Delay(200);
        Check(Directory.GetFiles(receiver.ReceiveDirectory, "test*").Length == 1, "no duplicate after interrupted restart");
        await restarted.CancelAsync(interrupted.Id); Check(restarted.Entries.Count == 0, "cancel persisted entry");
    }
    // Pair to a receiver which then goes offline; no file is claimed delivered.
    string offline = Path.Combine(root, "offline");
    string offlineId; int offlinePort;
    await using (var peer = await HopDropPeer.OpenAsync(new PeerOptions(Paths("offline"), 0, "Offline receiver")))
    { await peer.StartAsync(); await sender.PairQrAsync(peer.CreateQrUri([IPAddress.Loopback])); offlineId = peer.Id; offlinePort = peer.Port; }
    await using (var queue = new OutgoingQueue(sender, Paths("sender").DataDirectory))
    {
        await queue.InitializeAsync();
        var result = await queue.SendFilesAsync(offlineId, SendItem.From([source]));
        Check(result.Status == "queued" && queue.Entries.Count == 1 && !queue.Entries[0].Interrupted, "offline send persists without false success");
    }
    await using (var online = await HopDropPeer.OpenAsync(new PeerOptions(Paths("offline"), offlinePort, "Offline receiver")))
    await using (var queue = new OutgoingQueue(sender, Paths("sender").DataDirectory))
    {
        await queue.InitializeAsync(); Check(queue.Entries.Count == 1, "offline send survives restart");
        await online.StartAsync(); queue.Retry(offlineId);
        for (int i = 0; i < 100 && queue.Entries.Count != 0; i++) await Task.Delay(50);
        Check(queue.Entries.Count == 0 && File.ReadAllBytes(Path.Combine(online.ReceiveDirectory, "test.bin")).SequenceEqual(bytes), "queued file delivered after reconnect");
    }
    await sender.RecordBluetoothSentAsync([source]);
    Check((await sender.HistoryAsync()).Last() is { Direction: "sent", Transport: TransferTransport.Bluetooth }, "confirmed Bluetooth activity");
    Console.WriteLine("PASS: queue delivery, integrity, Arabic text, interrupted restart, offline persistence, cancellation, Bluetooth activity");
}
finally { Directory.Delete(root, true); }
