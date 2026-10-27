using HopDrop.Linux.Platform;

if (!OperatingSystem.IsLinux()) throw new Exception("Run these tests on Linux.");
if (args.Contains("--probe-real"))
{
    var hotspot = await new LinuxHotspot().ProbeAsync();
    var bluetooth = await new LinuxBluetooth().ProbeAsync();
    Console.WriteLine($"Hotspot: available={hotspot.Available}; reason={hotspot.Reason}");
    Console.WriteLine($"Bluetooth: available={bluetooth.Available}; reason={bluetooth.Reason}");
    return;
}

await ProbeHotspotAsync();
await StartHotspotAsync();
await ProbeBluetoothAsync();
await SendBluetoothAsync();
Console.WriteLine("Linux radio backend fake-adapter tests passed.");

static async Task ProbeHotspotAsync()
{
    var missing = new FakeRunner((file, args) => throw new System.ComponentModel.Win32Exception());
    var state = await new LinuxHotspot(missing).ProbeAsync();
    Check(!state.Available && state.Reason.Contains("not installed"), "missing nmcli");

    var radioOff = new FakeRunner((_, args) => args[0] == "radio" ? new(0, "disabled\n", "") : new(0, "running\n", ""));
    state = await new LinuxHotspot(radioOff).ProbeAsync();
    Check(!state.Available && state.Reason.Contains("turned off"), "radio off");

    var connected = new FakeRunner((_, args) => args[0] switch
    {
        "radio" => new(0, "enabled\n", ""),
        "-t" when args.Contains("RUNNING") => new(0, "running\n", ""),
        "-t" => new(0, "wlan0:wifi:connected\n", ""),
        "-g" => new(0, "yes\n", ""),
        _ => throw new Exception("unexpected command")
    });
    var hotspot = new LinuxHotspot(connected);
    state = await hotspot.ProbeAsync();
    Check(state.Available && state.Device == "wlan0" && state.DisconnectsCurrentWifi, "connected adapter warning");
    var refused = await hotspot.StartAsync("HopDrop", "secret123", false);
    Check(!refused.Success && connected.Calls.All(c => !c.Args.Contains("add")), "explicit Wi-Fi disconnect consent");

    var noAp = new FakeRunner((_, args) => args[0] == "radio" ? new(0, "enabled", "") :
        args.Contains("RUNNING") ? new(0, "running", "") :
        args.Contains("status") ? new(0, "wlan0:wifi:disconnected", "") : new(0, "no", ""));
    state = await new LinuxHotspot(noAp).ProbeAsync();
    Check(!state.Available && state.Reason.Contains("AP support"), "AP capability");
}

static async Task StartHotspotAsync()
{
    if (!OperatingSystem.IsLinux()) throw new PlatformNotSupportedException();
    string? uuid = null;
    bool passwordPrivate = false;
    var runner = new FakeRunner((_, args) =>
    {
        if (args.Contains("RUNNING")) return new(0, "running\n", "");
        if (args[0] == "radio") return new(0, "enabled\n", "");
        if (args.Contains("status")) return new(0, "wlan0:wifi:disconnected\n", "");
        if (args.Contains("WIFI-PROPERTIES.AP")) return new(0, "yes\n", "");
        if (args.Contains("add"))
        {
            uuid = args[args.IndexOf("connection.uuid") + 1];
            return new(0, "added", "");
        }
        if (args.Contains("up"))
        {
            string file = args[args.IndexOf("passwd-file") + 1];
            passwordPrivate = PrivateFile(file)
                && File.ReadAllText(file) == "wifi-sec.psk:secret123\n";
            return new(0, "activated", "");
        }
        if (args.Contains("GENERAL.CON-UUID")) return new(0, uuid + "\n", "");
        if (args.Contains("IP4.ADDRESS")) return new(0, "10.42.0.1/24\n", "");
        if (args.Contains("down") || args.Contains("delete")) return new(0, "", "");
        throw new Exception("unexpected command: " + string.Join(' ', args));
    });
    var hotspot = new LinuxHotspot(runner);
    var result = await hotspot.StartAsync("HopDrop", "secret123", false);
    Check(result.Success && result.Link!.Address == "10.42.0.1", "hotspot address");
    Check(passwordPrivate, "WPA key file mode and content");
    Check(runner.Calls.All(c => !c.Args.Contains("secret123")), "no key in process arguments");
    Check(await hotspot.RefreshAsync() is not null, "active UUID refresh");
    Check(await hotspot.StopAsync() is null && hotspot.Link is null, "stop");
    Check(runner.Calls.Any(c => c.Args.Contains("delete") && c.Args.Contains(uuid!)), "only owned UUID deleted");
    Check(runner.Calls.Where(c => c.Args.Contains("passwd-file")).All(c => !File.Exists(c.Args.Last())), "key file removed");

    var failed = new FakeRunner((_, args) => args.Contains("add") ? new(4, "", "permission denied") :
        args.Contains("RUNNING") ? new(0, "running", "") : args[0] == "radio" ? new(0, "enabled", "") :
        args.Contains("status") ? new(0, "wlan0:wifi:disconnected", "") : new(0, "yes", ""));
    result = await new LinuxHotspot(failed).StartAsync("HopDrop", "secret123", false);
    Check(!result.Success && result.Error!.Contains("permission denied"), "polkit refusal");

    string? failedUuid = null;
    var activationFailed = new FakeRunner((_, args) =>
    {
        if (args.Contains("RUNNING")) return new(0, "running", "");
        if (args[0] == "radio") return new(0, "enabled", "");
        if (args.Contains("status")) return new(0, "wlan0:wifi:disconnected", "");
        if (args.Contains("WIFI-PROPERTIES.AP")) return new(0, "yes", "");
        if (args.Contains("add")) { failedUuid = args[args.IndexOf("connection.uuid") + 1]; return new(0, "", ""); }
        if (args.Contains("up")) return new(4, "", "Not authorized by polkit");
        if (args.Contains("down")) return new(4, "", "not active");
        if (args.Contains("delete")) return new(0, "", "");
        throw new Exception("unexpected command");
    });
    result = await new LinuxHotspot(activationFailed).StartAsync("HopDrop", "secret123", false);
    Check(!result.Success && result.Error!.Contains("polkit"), "activation refusal");
    Check(activationFailed.Calls.Any(c => c.Args.Contains("delete") && c.Args.Contains(failedUuid!)), "failed profile cleanup");
}

static async Task ProbeBluetoothAsync()
{
    var missing = new FakeRunner((_, _) => new(1, "", ""));
    var state = await new LinuxBluetooth(missing).ProbeAsync();
    Check(!state.Available && state.Reason.Contains("controller"), "missing controller");
    var poweredOff = new FakeRunner((_, _) => new(0, "Controller AA:BB:CC:DD:EE:FF\nPowered: no\n", ""));
    state = await new LinuxBluetooth(poweredOff).ProbeAsync();
    Check(!state.Available && state.Reason.Contains("off"), "Bluetooth off");
}

static async Task SendBluetoothAsync()
{
    string path = Path.GetTempFileName();
    try
    {
        var runner = new FakeRunner((file, args) =>
        {
            if (file == "bluetoothctl" && args.Contains("devices")) return new(0, "Device AA:BB:CC:DD:EE:FF Test phone\n", "");
            if (file == "bluetoothctl") return new(0, "Controller AA:BB:CC:DD:EE:FF\nPowered: yes\n", "");
            if (args.Contains("--version")) return new(0, "2.0", "");
            if (args.Any(a => a.EndsWith(".ListActivatableNames"))) return new(0, "(['org.bluez.obex'],)", "");
            if (args.Any(a => a.EndsWith(".NameHasOwner"))) return new(0, "(false,)", "");
            if (args.Any(a => a.EndsWith(".CreateSession"))) return new(0, "(objectpath '/org/bluez/obex/client/session0',)", "");
            if (args.Any(a => a.EndsWith(".SendFile"))) return new(0, "(objectpath '/org/bluez/obex/client/session0/transfer0', {'Status': <'active'>})", "");
            if (args.Any(a => a.EndsWith(".Properties.Get"))) return new(0, "(<'complete'>,)", "");
            if (args.Any(a => a.EndsWith(".RemoveSession"))) return new(0, "()", "");
            throw new Exception("unexpected command: " + string.Join(' ', args));
        });
        var bluetooth = new LinuxBluetooth(runner);
        var devices = await bluetooth.ListPairedDevicesAsync();
        Check(devices.Error is null && devices.Devices.Count == 1 && devices.Devices[0].Name == "Test phone", "paired device list");
        var result = await bluetooth.SendFileAsync("AA:BB:CC:DD:EE:FF", path);
        Check(result.Delivery == BluetoothDelivery.Complete, "authoritative OBEX completion");
        Check(runner.Calls.Any(c => c.Args.Any(a => a.EndsWith(".RemoveSession"))), "session cleanup");
        result = await bluetooth.SendFileAsync("bad", path);
        Check(result.Delivery == BluetoothDelivery.Failed, "device address validation");

        var unconfirmed = new FakeRunner((file, args) =>
        {
            if (file == "bluetoothctl" && args.Contains("devices")) return new(0, "Device AA:BB:CC:DD:EE:FF Test phone\n", "");
            if (file == "bluetoothctl") return new(0, "Controller AA:BB:CC:DD:EE:FF\nPowered: yes\n", "");
            if (args.Contains("--version")) return new(0, "2.0", "");
            if (args.Any(a => a.EndsWith(".ListActivatableNames"))) return new(0, "(['org.bluez.obex'],)", "");
            if (args.Any(a => a.EndsWith(".NameHasOwner"))) return new(0, "(false,)", "");
            if (args.Any(a => a.EndsWith(".CreateSession"))) return new(0, "(objectpath '/org/bluez/obex/client/session0',)", "");
            if (args.Any(a => a.EndsWith(".SendFile"))) return new(0, "(objectpath '/org/bluez/obex/client/session0/transfer0', {'Status': <'active'>})", "");
            if (args.Any(a => a.EndsWith(".Properties.Get"))) return new(1, "", "UnknownObject");
            if (args.Any(a => a.EndsWith(".RemoveSession"))) return new(0, "()", "");
            throw new Exception("unexpected command");
        });
        result = await new LinuxBluetooth(unconfirmed).SendFileAsync("AA:BB:CC:DD:EE:FF", path);
        Check(result.Delivery == BluetoothDelivery.Unconfirmed, "disappearing transfer is not success");
    }
    finally { File.Delete(path); }
}

static void Check(bool condition, string name)
{
    if (!condition) throw new Exception("Failed: " + name);
}

static bool PrivateFile(string path) => OperatingSystem.IsLinux() &&
    File.GetUnixFileMode(path) == (UnixFileMode.UserRead | UnixFileMode.UserWrite);

sealed class FakeRunner(Func<string, List<string>, CommandResult> run) : ILinuxCommandRunner
{
    public List<(string File, List<string> Args)> Calls { get; } = [];
    public Task<CommandResult> RunAsync(string file, IReadOnlyList<string> args, CancellationToken stop)
    {
        var copy = args.ToList(); Calls.Add((file, copy));
        return Task.FromResult(run(file, copy));
    }
}
