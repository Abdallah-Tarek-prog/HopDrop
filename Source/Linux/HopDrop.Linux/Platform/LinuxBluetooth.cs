using System.Diagnostics;
using System.Text.RegularExpressions;

namespace HopDrop.Linux.Platform;

public sealed record BluetoothCapability(bool Available, string Reason, bool NativeReceiveAvailable);
public sealed record BluetoothDevice(string Address, string Name);
public sealed record BluetoothDeviceList(IReadOnlyList<BluetoothDevice> Devices, string? Error);
public enum BluetoothDelivery { Complete, Failed, Unconfirmed }
public sealed record BluetoothSendResult(BluetoothDelivery Delivery, string Message);

/// <summary>BlueZ OBEX Object Push. A caller chooses the destination device and files explicitly.</summary>
public sealed class LinuxBluetooth
{
    private const string Service = "org.bluez.obex";
    private const string Root = "/org/bluez/obex";
    private readonly ILinuxCommandRunner _runner;
    public LinuxBluetooth() : this(new LinuxCommandRunner()) { }
    internal LinuxBluetooth(ILinuxCommandRunner runner) => _runner = runner;

    public async Task<BluetoothCapability> ProbeAsync(CancellationToken stop = default)
    {
        if (!OperatingSystem.IsLinux()) return new(false, "Linux is required.", false);
        bool native = FindExecutable("blueman-applet") is not null;
        try
        {
            var controller = await _runner.RunAsync("bluetoothctl", ["show"], stop);
            if (!controller.Ok || !controller.Output.Contains("Controller ", StringComparison.Ordinal))
                return new(false, "No BlueZ Bluetooth controller is available.", false);
            if (!controller.Output.Contains("Powered: yes", StringComparison.Ordinal))
                return new(false, "Bluetooth is off. Turn it on in system settings.", false);
            var tool = await _runner.RunAsync("gdbus", ["--version"], stop);
            if (!tool.Ok) return new(false, "gdbus is unavailable; install the GLib D-Bus tools.", false);
            var names = await BusAsync("/org/freedesktop/DBus", "org.freedesktop.DBus.ListActivatableNames", [], stop,
                destination: "org.freedesktop.DBus");
            var owner = await BusAsync("/org/freedesktop/DBus", "org.freedesktop.DBus.NameHasOwner", [Service], stop,
                destination: "org.freedesktop.DBus");
            if (!names.Ok && !owner.Ok) return new(false, "The user D-Bus session is unavailable.", false);
            if (!names.Output.Contains("'" + Service + "'", StringComparison.Ordinal) &&
                !owner.Output.Contains("true", StringComparison.Ordinal))
                return new(false, "BlueZ OBEX is not installed or running. Install/start the user obex service.", false);
            return new(true, "", native);
        }
        catch (System.ComponentModel.Win32Exception e)
        { return new(false, "BlueZ tools are unavailable: " + e.Message, false); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        { return new(false, "Cannot inspect Bluetooth: " + e.Message, false); }
    }

    /// <summary>Read-only list for a user-controlled destination picker.</summary>
    public async Task<BluetoothDeviceList> ListPairedDevicesAsync(CancellationToken stop = default)
    {
        var capability = await ProbeAsync(stop);
        if (!capability.Available) return new([], capability.Reason);
        var listed = await _runner.RunAsync("bluetoothctl", ["devices", "Paired"], stop);
        if (!listed.Ok) return new([], "Cannot list paired Bluetooth devices: " + Failure(listed));
        var devices = new List<BluetoothDevice>();
        foreach (string line in listed.Output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var match = Regex.Match(line.TrimEnd('\r'), @"\ADevice (?<address>(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}) (?<name>.+)\z");
            if (match.Success) devices.Add(new(match.Groups["address"].Value, match.Groups["name"].Value));
        }
        return new(devices, null);
    }

    /// <summary>Returns Complete only after BlueZ Transfer1 reports Status=complete.</summary>
    public async Task<BluetoothSendResult> SendFileAsync(string deviceAddress, string filePath,
        IProgress<string>? status = null, CancellationToken stop = default)
    {
        if (!Regex.IsMatch(deviceAddress, @"\A(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\z"))
            return new(BluetoothDelivery.Failed, "Choose a valid Bluetooth device address.");
        if (!File.Exists(filePath))
            return new(BluetoothDelivery.Failed, "The selected file is unavailable.");
        var paired = await ListPairedDevicesAsync(stop);
        if (paired.Error is not null) return new(BluetoothDelivery.Failed, paired.Error);
        if (!paired.Devices.Any(d => d.Address.Equals(deviceAddress, StringComparison.OrdinalIgnoreCase)))
            return new(BluetoothDelivery.Failed, "Choose a paired Bluetooth device in the system settings first.");
        string? session = null;
        try
        {
            var created = await BusAsync(Root, "org.bluez.obex.Client1.CreateSession",
                [deviceAddress, "{'Target': <'opp'>}"], stop);
            session = created.Ok ? ObjectPath(created.Output) : null;
            if (session is null) return new(BluetoothDelivery.Failed,
                "BlueZ could not connect to the selected device: " + Failure(created));
            var sent = await BusAsync(session, "org.bluez.obex.ObjectPush1.SendFile", [Path.GetFullPath(filePath)], stop);
            string? transfer = sent.Ok ? ObjectPath(sent.Output) : null;
            if (transfer is null) return new(BluetoothDelivery.Failed,
                "BlueZ rejected the file: " + Failure(sent));
            string? current = TransferStatus(sent.Output);
            while (true)
            {
                if (current == "complete") return new(BluetoothDelivery.Complete, "BlueZ confirmed delivery.");
                if (current == "error") return new(BluetoothDelivery.Failed, "BlueZ reported a transfer error.");
                if (current is not null) status?.Report(current);
                await Task.Delay(300, stop);
                var observed = await BusAsync(transfer, "org.freedesktop.DBus.Properties.Get",
                    ["org.bluez.obex.Transfer1", "Status"], stop);
                if (!observed.Ok) return new(BluetoothDelivery.Unconfirmed,
                    "The OBEX transfer disappeared before completion could be confirmed: " + Failure(observed));
                current = TransferStatus(observed.Output);
                if (current is null) return new(BluetoothDelivery.Unconfirmed, "BlueZ returned an unknown transfer status.");
            }
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested)
        { return new(BluetoothDelivery.Unconfirmed, "Transfer monitoring was canceled; delivery is unconfirmed."); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        { return new(BluetoothDelivery.Failed, "Bluetooth transfer failed: " + e.Message); }
        finally
        {
            if (session is not null)
            {
                try { await BusAsync(Root, "org.bluez.obex.Client1.RemoveSession", [session], CancellationToken.None); }
                catch (Exception) { /* The BlueZ service may have gone away. */ }
            }
        }
    }

    /// <summary>Starts the installed native consent agent. It has no HopDrop completion callback.</summary>
    public (bool Opened, string Message) OpenNativeReceiveAgent()
    {
        string? applet = FindExecutable("blueman-applet");
        if (applet is null) return (false, "Blueman's receive agent is not installed. Install Blueman and enable its transfer plugin.");
        try
        {
            if (Process.Start(new ProcessStartInfo(applet) { UseShellExecute = false, CreateNoWindow = true }) is null)
                return (false, "Could not open Blueman.");
            return (true, "Opened Blueman. Accept incoming files in its system UI; HopDrop cannot verify their completion.");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        { return (false, "Could not open Blueman: " + e.Message); }
    }

    private Task<CommandResult> BusAsync(string path, string method, IReadOnlyList<string> arguments,
        CancellationToken stop, string destination = Service)
    {
        var args = new List<string> { "call", "--session", "--dest", destination, "--object-path", path, "--method", method };
        args.AddRange(arguments);
        return _runner.RunAsync("gdbus", args, stop);
    }
    private static string? ObjectPath(string output)
    {
        var match = Regex.Match(output, @"(?:objectpath\s*)?'(?<path>/org/bluez/obex/client/session[0-9]+(?:/transfer[0-9]+)?)'");
        return match.Success ? match.Groups["path"].Value : null;
    }
    private static string? TransferStatus(string output)
    {
        var match = Regex.Match(output, @"'(?<status>queued|active|suspended|complete|error)'");
        return match.Success ? match.Groups["status"].Value : null;
    }
    private static string Failure(CommandResult result) => result.Ok ? "Unexpected BlueZ response."
        : string.IsNullOrWhiteSpace(result.Error) ? $"exit code {result.ExitCode}" : result.Error.Trim();
    private static string? FindExecutable(string name)
    {
        foreach (string folder in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (folder.Length == 0) continue;
            string path = Path.Combine(folder, name);
            if (File.Exists(path)) return path;
        }
        return null;
    }
}
