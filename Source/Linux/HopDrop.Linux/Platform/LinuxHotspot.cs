using System.Net;
using System.Security.Cryptography;
using System.Text;
using HopDrop.Core;

namespace HopDrop.Linux.Platform;

public sealed record HotspotCapability(bool Available, string Reason, string? Device, bool DisconnectsCurrentWifi);
public sealed record HotspotStartResult(DirectLinkInfo? Link, string? Error)
{
    public bool Success => Link is not null;
}

/// <summary>An explicit NetworkManager infrastructure AP, not Wi-Fi Direct concurrency.</summary>
public sealed class LinuxHotspot
{
    private readonly ILinuxCommandRunner _runner;
    private string? _ownedUuid;
    private string? _device;
    private DirectLinkInfo? _link;

    public LinuxHotspot() : this(new LinuxCommandRunner()) { }
    internal LinuxHotspot(ILinuxCommandRunner runner) => _runner = runner;
    public DirectLinkInfo? Link => _link;

    public async Task<HotspotCapability> ProbeAsync(CancellationToken stop = default)
    {
        if (!OperatingSystem.IsLinux()) return new(false, "Linux is required.", null, false);
        CommandResult running;
        try { running = await NmAsync(["-t", "-f", "RUNNING", "general"], stop); }
        catch (System.ComponentModel.Win32Exception) { return new(false, "NetworkManager (nmcli) is not installed.", null, false); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        { return new(false, "Cannot launch NetworkManager: " + e.Message, null, false); }
        if (!running.Ok || running.Output.Trim() != "running")
            return new(false, "NetworkManager is not running or cannot be reached.", null, false);
        var radio = await NmAsync(["radio", "wifi"], stop);
        if (!radio.Ok) return new(false, "Cannot read the Wi-Fi radio state: " + Failure(radio), null, false);
        if (radio.Output.Trim() != "enabled") return new(false, "Wi-Fi is turned off. Turn it on in system settings.", null, false);
        var devices = await NmAsync(["-t", "-f", "DEVICE,TYPE,STATE", "device", "status"], stop);
        if (!devices.Ok) return new(false, "Cannot inspect Wi-Fi adapters: " + Failure(devices), null, false);
        var wifi = devices.Output.Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .Select(ParseDevice).Where(d => d.HasValue && d.Value.Type == "wifi").Select(d => d!.Value).ToList();
        if (wifi.Count == 0) return new(false, "No NetworkManager Wi-Fi adapter was found.", null, false);
        string? reason = null;
        foreach (var device in wifi.OrderBy(d => d.State != "disconnected"))
        {
            if (device.State is "unmanaged" or "unavailable")
            { reason = $"Wi-Fi adapter {device.Name} is {device.State}."; continue; }
            var ap = await NmAsync(["-g", "WIFI-PROPERTIES.AP", "device", "show", device.Name], stop);
            if (!ap.Ok || ap.Output.Trim() != "yes")
            { reason = $"Wi-Fi adapter {device.Name} does not report AP support."; continue; }
            return new(true, "", device.Name, device.State != "disconnected");
        }
        return new(false, reason ?? "No managed AP-capable Wi-Fi adapter is available.", null, false);
    }

    public async Task<HotspotStartResult> StartAsync(string ssid, string key, bool allowDisconnectCurrentWifi, CancellationToken stop = default)
    {
        if (!OperatingSystem.IsLinux()) return new(null, "Linux is required.");
        if (_ownedUuid is not null) return new(_link, "A HopDrop hotspot is already starting or active.");
        if (Encoding.UTF8.GetByteCount(ssid) is < 1 or > 32 || ssid.Any(char.IsControl) ||
            key.Length is < 8 or > 63 || key.Any(c => c < 0x20 || c > 0x7e))
            return new(null, "Use an SSID of 1–32 UTF-8 bytes and a WPA2 key of 8–63 printable ASCII characters.");
        var capability = await ProbeAsync(stop);
        if (!capability.Available) return new(null, capability.Reason);
        if (capability.DisconnectsCurrentWifi && !allowDisconnectCurrentWifi)
            return new(null, "This adapter is connected to Wi-Fi. Starting its hotspot may disconnect that network.");
        string uuid = Guid.NewGuid().ToString();
        string device = capability.Device!;
        // Never pass the PSK as a process argument. NetworkManager receives it from a private temporary file.
        var add = await NmAsync(["connection", "add", "save", "no", "type", "wifi", "ifname", device,
            "con-name", "HopDrop hotspot " + uuid, "connection.uuid", uuid, "connection.autoconnect", "no",
            "802-11-wireless.mode", "ap", "802-11-wireless.ssid", ssid,
            "wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk-flags", "2",
            "ipv4.method", "shared", "ipv6.method", "disabled"], stop);
        if (!add.Ok) return new(null, "NetworkManager refused to create a temporary hotspot profile: " + Failure(add));
        _ownedUuid = uuid;
        _device = device;
        string passwordFile = Path.Combine(Path.GetTempPath(), "hopdrop-hotspot-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(12)));
        try
        {
            await using (var file = new FileStream(passwordFile, new FileStreamOptions
            {
                Mode = FileMode.CreateNew, Access = FileAccess.Write,
                UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite
            }))
            {
                byte[] contents = Encoding.UTF8.GetBytes("wifi-sec.psk:" + key + "\n");
                await file.WriteAsync(contents, stop);
                CryptographicOperations.ZeroMemory(contents);
            }
            var up = await NmAsync(["--wait", "45", "connection", "up", "uuid", uuid,
                "ifname", device, "passwd-file", passwordFile], stop);
            if (!up.Ok) return new(null, "NetworkManager could not activate the hotspot: " + Failure(up).Replace(key, "[redacted]", StringComparison.Ordinal));
            var actualUuid = await NmAsync(["-g", "GENERAL.CON-UUID", "device", "show", device], stop);
            if (!actualUuid.Ok || actualUuid.Output.Trim() != uuid)
                return new(null, "The hotspot profile is no longer active on the selected adapter.");
            var address = await NmAsync(["-g", "IP4.ADDRESS", "device", "show", device], stop);
            string? ip = address.Ok ? PrivateAddress(address.Output) : null;
            if (ip is null) return new(null, "NetworkManager did not assign the hotspot a private IPv4 address.");
            _link = new DirectLinkInfo(ssid, key, ip);
            return new(_link, null);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        { return new(null, "Could not create the hotspot: " + e.Message); }
        finally
        {
            try { File.Delete(passwordFile); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            if (_link is null) await StopAsync(CancellationToken.None);
        }
    }

    public async Task<DirectLinkInfo?> RefreshAsync(CancellationToken stop = default)
    {
        if (_ownedUuid is null || _device is null || _link is null) return null;
        var active = await NmAsync(["-g", "GENERAL.CON-UUID", "device", "show", _device], stop);
        if (!active.Ok || active.Output.Trim() != _ownedUuid) _link = null;
        return _link;
    }

    public async Task<string?> StopAsync(CancellationToken stop = default)
    {
        _link = null;
        if (_ownedUuid is null) return null;
        string uuid = _ownedUuid;
        var down = await NmAsync(["connection", "down", "uuid", uuid], stop);
        var deleted = await NmAsync(["connection", "delete", "uuid", uuid], stop);
        if (!deleted.Ok) return "Could not remove HopDrop's temporary hotspot profile: " + Failure(deleted);
        _ownedUuid = null; _device = null;
        return down.Ok ? null : "Hotspot profile removed; NetworkManager could not confirm deactivation: " + Failure(down);
    }

    private Task<CommandResult> NmAsync(IReadOnlyList<string> args, CancellationToken stop) => _runner.RunAsync("nmcli", args, stop);
    private static string Failure(CommandResult result) =>
        string.IsNullOrWhiteSpace(result.Error) ? $"exit code {result.ExitCode}" : result.Error.Trim();
    private static (string Name, string Type, string State)? ParseDevice(string line)
    {
        var fields = new List<string>(); var field = new StringBuilder(); bool escape = false;
        foreach (char c in line.TrimEnd('\r'))
        {
            if (escape) { field.Append(c); escape = false; }
            else if (c == '\\') escape = true;
            else if (c == ':') { fields.Add(field.ToString()); field.Clear(); }
            else field.Append(c);
        }
        fields.Add(field.ToString());
        return fields.Count >= 3 && fields[0].Length > 0 ? (fields[0], fields[1], fields[2]) : null;
    }
    private static string? PrivateAddress(string output)
    {
        foreach (string cidr in output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            if (IPAddress.TryParse(cidr.Trim().Split('/')[0], out var ip) &&
                ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                !IPAddress.IsLoopback(ip) && NetworkAddresses.IsPrivateOrLoopback(ip)) return ip.ToString();
        }
        return null;
    }
}
