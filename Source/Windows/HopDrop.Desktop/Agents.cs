using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using HopDrop.Core;

namespace HopDrop.Desktop;

/// <summary>
/// Lets AI agents on this PC (Claude, Codex, Cursor… through MCP, or scripts through "HopDrop.exe agent") use the running
/// HopDrop: list the paired devices, send files and folders to them, and find files that arrived. Agents can't pair
/// devices, change settings or accept files. Off until the user turns on Settings → AI agents; only this Windows user
/// can connect. Agents talk to the running app (window, tray or hidden receiver), so the user sees what they send.
/// </summary>
internal static class AgentBridge
{
    private const string Off = "AI agent access is off. Turn it on in HopDrop → Settings → AI agents.";
    private static HopDropPeer? _peer;
    private static readonly ConcurrentDictionary<Guid, AgentSend> Sends = new();
    private static readonly ConcurrentDictionary<string, TransferProgress> Outgoing = new();

    private sealed class AgentSend
    {
        public required Guid Id { get; init; }
        public required PairedDevice Device { get; init; }
        public required int Files { get; init; }
        public required long Bytes { get; init; }
        public Task<TransferResult> Task { get; set; } = null!;
    }

    /// <summary>The agents' pipe for HopDrop's data folder (one HopDrop runs per data folder).</summary>
    public static string PipeName(string dataDirectory) =>
        "HopDrop-agent-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(dataDirectory).ToUpperInvariant())))[..24];

    public static void Start(HopDropPeer peer, string dataDirectory, CancellationToken stop)
    {
        _peer = peer;
        peer.Progress += p => { if (!p.Incoming) Outgoing[p.PeerId] = p; };
        _ = AcceptAsync(PipeName(dataDirectory), stop);
    }

    private static async Task AcceptAsync(string name, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            NamedPipeServerStream? server = null;
            try
            {
                server = new NamedPipeServerStream(name, PipeDirection.InOut, NamedPipeServerStream.MaxAllowedServerInstances, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(stop);
                _ = ServeAsync(server, stop);
                server = null;
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
            catch (Exception e) { Debug.WriteLine(e); try { await Task.Delay(1000, stop); } catch (OperationCanceledException) { break; } }
            finally { server?.Dispose(); }
        }
    }

    /// <summary>One request per connection: a line of JSON in, a line of JSON out.</summary>
    private static async Task ServeAsync(NamedPipeServerStream pipe, CancellationToken stop)
    {
        await using var _ = pipe;
        try
        {
            using var reader = new StreamReader(pipe, new UTF8Encoding(false), false, 4096, true);
            string? line = await reader.ReadLineAsync(stop);
            if (line is null) return;
            JsonObject reply;
            try { reply = await HandleAsync(JsonNode.Parse(line)?.AsObject() ?? throw new AgentException("Empty request."), stop); reply["ok"] = true; }
            catch (AgentException e) { reply = new JsonObject { ["ok"] = false, ["error"] = e.Message }; }
            catch (Exception e) when (e is JsonException or InvalidOperationException) { reply = new JsonObject { ["ok"] = false, ["error"] = "HopDrop couldn't read that request." }; }
            byte[] bytes = Encoding.UTF8.GetBytes(reply.ToJsonString() + "\n");
            await pipe.WriteAsync(bytes, stop);
            await pipe.FlushAsync(stop);
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or ObjectDisposedException) { }
    }

    private static async Task<JsonObject> HandleAsync(JsonObject request, CancellationToken stop)
    {
        var peer = _peer ?? throw new AgentException("HopDrop is still starting. Try again in a moment.");
        string op = request["op"]?.GetValue<string>() ?? "";
        if (op == "hello") return new JsonObject { ["version"] = Version, ["enabled"] = Preferences.AgentsEnabled };
        if (!Preferences.AgentsEnabled) throw new AgentException(Off);
        return op switch
        {
            "devices" => await DevicesAsync(peer, stop),
            "send" => await SendAsync(peer, request, stop),
            "status" => Status(request),
            "recent" => await RecentAsync(peer, request, stop),
            "wait" => await WaitAsync(peer, request, stop),
            _ => throw new AgentException($"Unknown request \"{op}\".")
        };
    }

    internal static string Version => typeof(AgentBridge).Assembly.GetName().Version?.ToString(3) ?? "2";

    private static async Task<JsonObject> DevicesAsync(HopDropPeer peer, CancellationToken stop)
    {
        var devices = await peer.DevicesAsync(stop);
        // Ask who's around now, so "online" is current even when HopDrop has been idle in the background.
        try { await peer.QueryNearbyAsync(stop); await Task.Delay(800, stop); } catch (Exception e) when (e is not OperationCanceledException) { }
        var nearby = peer.Nearby;
        var list = new JsonArray();
        foreach (var d in devices.OrderBy(d => d.Alias ?? d.Name, StringComparer.OrdinalIgnoreCase))
            list.Add(new JsonObject
            {
                ["name"] = d.Alias ?? d.Name, ["id"] = d.Id, ["platform"] = d.Platform == "android" ? "Android phone" : "Windows PC",
                ["online"] = nearby.Any(n => n.Id == d.Id), ["trusted"] = d.Trusted, ["lastSeen"] = d.LastSeen.ToString("o")
            });
        return new JsonObject { ["thisComputer"] = peer.Name, ["receiveFolder"] = peer.ReceiveDirectory, ["devices"] = list };
    }

    private static async Task<JsonObject> SendAsync(HopDropPeer peer, JsonObject request, CancellationToken stop)
    {
        var device = await ResolveAsync(peer, request["device"]?.GetValue<string>(), stop);
        var paths = request["paths"]?.AsArray().Select(p => p?.GetValue<string>() ?? "").Where(p => p.Length > 0).ToList() ?? [];
        if (paths.Count == 0) throw new AgentException("Give at least one file or folder path.");
        foreach (string path in paths)
        {
            if (!Path.IsPathFullyQualified(path)) throw new AgentException($"Use a full path (like C:\\Users\\me\\report.pdf), not \"{path}\".");
            if (!File.Exists(path) && !Directory.Exists(path)) throw new AgentException($"Not found: {path}");
        }
        List<SendItem> items;
        try { items = SendItem.From(paths); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { throw new AgentException("HopDrop couldn't read a folder: " + e.Message); }
        if (items.Count == 0) throw new AgentException("Nothing to send: the folders are empty (hidden and system files are left out).");
        long bytes = items.Sum(i => { try { return new FileInfo(i.Path).Length; } catch { return 0L; } });
        var job = new AgentSend { Id = Guid.NewGuid(), Device = device, Files = items.Count, Bytes = bytes };
        Outgoing.TryRemove(device.Id, out TransferProgress? _);
        job.Task = Task.Run(() => peer.SendAsync(device.Id, items));
        Sends[job.Id] = job;
        int wait = Math.Clamp(request["wait"]?.GetValue<int>() ?? 50, 0, 600);
        try { await Task.WhenAny(job.Task, Task.Delay(TimeSpan.FromSeconds(wait), stop)); } catch (OperationCanceledException) { }
        return Describe(job);
    }

    private static JsonObject Status(JsonObject request)
    {
        string? id = request["id"]?.GetValue<string>();
        if (id is null || !Guid.TryParse(id, out var key) || !Sends.TryGetValue(key, out var job)) throw new AgentException("No transfer with that id. It may be from before HopDrop restarted.");
        return Describe(job);
    }

    private static JsonObject Describe(AgentSend job)
    {
        string name = job.Device.Alias ?? job.Device.Name;
        var reply = new JsonObject { ["transferId"] = job.Id.ToString(), ["device"] = name };
        if (!job.Task.IsCompleted)
        {
            reply["status"] = "sending";
            if (Outgoing.TryGetValue(job.Device.Id, out var p))
            {
                if (p.WaitingForApproval) reply["note"] = $"Waiting for someone to accept on {name}.";
                else if (p.Reconnecting) reply["note"] = "The connection dropped; HopDrop is reconnecting and will continue where it stopped.";
                reply["percent"] = p.Percent; reply["bytesDone"] = p.BytesDone; reply["totalBytes"] = p.TotalBytes;
                if (p.Eta is { } eta) reply["secondsLeft"] = (int)eta.TotalSeconds;
            }
            else reply["note"] = $"Connecting to {name}…";
            reply["files"] = job.Files;
            return reply;
        }
        if (job.Task.IsCompletedSuccessfully)
        {
            var r = job.Task.Result;
            reply["status"] = "sent"; reply["files"] = r.SavedPaths.Count; reply["bytes"] = r.Bytes;
            reply["savedIn"] = $"{r.ReceiveFolderDisplayName} on {name}"; reply["seconds"] = Math.Round(r.Duration.TotalSeconds, 1);
            return reply;
        }
        var error = job.Task.Exception?.GetBaseException() ?? new Exception("Cancelled");
        reply["status"] = "failed"; reply["error"] = Platform.ExplainException(error, name);
        return reply;
    }

    private static async Task<JsonObject> RecentAsync(HopDropPeer peer, JsonObject request, CancellationToken stop)
    {
        int limit = Math.Clamp(request["limit"]?.GetValue<int>() ?? 10, 1, 50);
        string direction = request["direction"]?.GetValue<string>() ?? "all";
        var devices = await peer.DevicesAsync(stop);
        var list = new JsonArray();
        foreach (var h in (await peer.HistoryAsync(stop)).Where(h => direction == "all" || h.Direction == direction).OrderByDescending(h => h.At).Take(limit))
        {
            var device = devices.FirstOrDefault(d => d.Id == h.PeerId);
            list.Add(new JsonObject
            {
                ["at"] = h.At.ToLocalTime().ToString("o"), ["direction"] = h.Direction,
                ["device"] = h.PeerId == "bluetooth" ? "Bluetooth" : device?.Alias ?? device?.Name ?? "a removed device",
                ["result"] = h.Result == "ok" ? "ok" : Platform.ExplainError(h.Result, device?.Alias ?? device?.Name ?? "the device"),
                ["fileCount"] = h.FileCount, ["files"] = new JsonArray(h.Files.Take(200).Select(f => (JsonNode?)f).ToArray()),
                ["savedIn"] = h.Folder
            });
        }
        return new JsonObject { ["receiveFolder"] = peer.ReceiveDirectory, ["transfers"] = list };
    }

    private static async Task<JsonObject> WaitAsync(HopDropPeer peer, JsonObject request, CancellationToken stop)
    {
        int timeout = Math.Clamp(request["timeout"]?.GetValue<int>() ?? 50, 1, 900);
        string? from = request["device"]?.GetValue<string>();
        string? fromId = string.IsNullOrWhiteSpace(from) ? null : (await ResolveAsync(peer, from, stop)).Id;
        var arrived = new TaskCompletionSource<TransferResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Finished(TransferResult r) { if (r.Incoming && r.SavedPaths.Count > 0 && (fromId is null || r.PeerId == fromId)) arrived.TrySetResult(r); }
        peer.TransferFinished += Finished;
        try
        {
            try { await Task.WhenAny(arrived.Task, Task.Delay(TimeSpan.FromSeconds(timeout), stop)); } catch (OperationCanceledException) { }
            if (!arrived.Task.IsCompleted) return new JsonObject { ["status"] = "nothing yet", ["note"] = $"No files arrived in {timeout} s. Call again to keep waiting." };
            var r = arrived.Task.Result;
            return new JsonObject
            {
                ["status"] = r.ErrorCode is null ? "received" : "partly received", ["device"] = r.PeerName, ["fileCount"] = r.SavedPaths.Count, ["bytes"] = r.Bytes,
                ["files"] = new JsonArray(r.SavedPaths.Take(500).Select(f => (JsonNode?)f).ToArray()), ["receiveFolder"] = peer.ReceiveDirectory
            };
        }
        finally { peer.TransferFinished -= Finished; }
    }

    /// <summary>A paired device by id or name (exact, then partial); with no name, the only paired device.</summary>
    private static async Task<PairedDevice> ResolveAsync(HopDropPeer peer, string? wanted, CancellationToken stop)
    {
        var devices = await peer.DevicesAsync(stop);
        if (devices.Count == 0) throw new AgentException("No devices are paired yet. The user pairs a device once in HopDrop → Devices.");
        string Names() => string.Join(", ", devices.Select(d => d.Alias ?? d.Name));
        if (string.IsNullOrWhiteSpace(wanted))
            return devices.Count == 1 ? devices[0] : throw new AgentException($"Say which device: {Names()}.");
        wanted = wanted.Trim();
        var exact = devices.Where(d => d.Id.Equals(wanted, StringComparison.OrdinalIgnoreCase) || string.Equals(d.Alias ?? d.Name, wanted, StringComparison.OrdinalIgnoreCase)
            || d.Name.Equals(wanted, StringComparison.OrdinalIgnoreCase)).ToList();
        if (exact.Count == 1) return exact[0];
        var partial = devices.Where(d => (d.Alias ?? d.Name).Contains(wanted, StringComparison.OrdinalIgnoreCase) || d.Name.Contains(wanted, StringComparison.OrdinalIgnoreCase)).ToList();
        if (partial.Count == 1) return partial[0];
        throw new AgentException(partial.Count > 1 || exact.Count > 1 ? $"\"{wanted}\" matches several devices: {Names()}. Use the full name." : $"No paired device called \"{wanted}\". Paired: {Names()}.");
    }
}

internal sealed class AgentException(string message) : Exception(message);

/// <summary>The agent side: connects to the running HopDrop for a data folder (starting it in the tray if needed).</summary>
internal static class AgentClient
{
    public static async Task<JsonObject> CallAsync(string dataDirectory, JsonObject request, TimeSpan timeout)
    {
        await using var pipe = await ConnectAsync(dataDirectory);
        byte[] bytes = Encoding.UTF8.GetBytes(request.ToJsonString() + "\n");
        await pipe.WriteAsync(bytes);
        await pipe.FlushAsync();
        using var reader = new StreamReader(pipe, new UTF8Encoding(false));
        using var stop = new CancellationTokenSource(timeout);
        string? line;
        try { line = await reader.ReadLineAsync(stop.Token); }
        catch (OperationCanceledException) { throw new AgentException("HopDrop didn't answer in time."); }
        return JsonNode.Parse(line ?? throw new AgentException("HopDrop closed the connection."))?.AsObject() ?? throw new AgentException("HopDrop sent an empty answer.");
    }

    private static async Task<NamedPipeClientStream> ConnectAsync(string dataDirectory)
    {
        string name = AgentBridge.PipeName(dataDirectory);
        var pipe = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        try { await pipe.ConnectAsync(1500); return pipe; }
        catch (TimeoutException) { await pipe.DisposeAsync(); }
        // HopDrop isn't running for this data folder: start it in the tray (visible, so the user knows), then wait for it.
        string exe = Store.Packaged ? Store.Alias : Environment.ProcessPath ?? throw new AgentException("HopDrop.exe couldn't be found.");
        var start = new ProcessStartInfo(exe) { UseShellExecute = true, WindowStyle = ProcessWindowStyle.Minimized };
        start.ArgumentList.Add("--minimized"); start.ArgumentList.Add("--data"); start.ArgumentList.Add(Path.GetFullPath(dataDirectory));
        try { Process.Start(start); }
        catch (Exception e) { throw new AgentException("HopDrop isn't running and couldn't be started: " + e.Message); }
        var deadline = DateTime.UtcNow.AddSeconds(25);
        while (true)
        {
            pipe = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            try { await pipe.ConnectAsync(2000); return pipe; }
            catch (TimeoutException) when (DateTime.UtcNow < deadline) { await pipe.DisposeAsync(); }
            catch (TimeoutException) { await pipe.DisposeAsync(); throw new AgentException("HopDrop didn't start. Open it once by hand, then try again."); }
        }
    }
}

/// <summary>
/// "HopDrop.exe --mcp": a Model Context Protocol server over standard input/output for AI agents (Claude Code, Claude
/// Desktop, Codex, Cursor, VS Code…). Each tool is one request to the running HopDrop through <see cref="AgentClient"/>.
/// </summary>
internal static class McpServer
{
    private static readonly string[] Versions = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
    private static readonly SemaphoreSlim WriteLock = new(1, 1);
    private static Stream _out = Stream.Null;

    public static int Run(string dataDirectory)
    {
        _out = Console.OpenStandardOutput();
        using var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        var running = new List<Task>();
        while (input.ReadLine() is { } line)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            JsonObject message;
            try { message = JsonNode.Parse(line)?.AsObject() ?? throw new JsonException(); }
            catch (Exception e) when (e is JsonException or InvalidOperationException) { Write(Error(null, -32700, "Parse error")); continue; }
            if (message["method"]?.GetValue<string>() is not { } method || !message.ContainsKey("id")) continue; // notifications, and replies we never asked for
            JsonNode? id = message["id"]?.DeepClone();
            JsonObject? parameters = message["params"] as JsonObject;
            running.RemoveAll(t => t.IsCompleted);
            // Requests run side by side: a long wait_for_files mustn't block pings or other tools.
            running.Add(Task.Run(async () => Write(await AnswerAsync(dataDirectory, id, method, parameters))));
        }
        Task.WaitAll([.. running]);
        return 0;
    }

    private static async Task<JsonObject> AnswerAsync(string dataDirectory, JsonNode? id, string method, JsonObject? parameters)
    {
        try
        {
            return method switch
            {
                "initialize" => Result(id, Initialize(parameters)),
                "ping" => Result(id, new JsonObject()),
                "tools/list" => Result(id, new JsonObject { ["tools"] = Tools() }),
                "tools/call" => Result(id, await CallToolAsync(dataDirectory, parameters)),
                _ => Error(id, -32601, "Method not found: " + method)
            };
        }
        catch (Exception e) { return Error(id, -32603, e.Message); }
    }

    private static JsonObject Initialize(JsonObject? parameters)
    {
        string asked = parameters?["protocolVersion"]?.GetValue<string>() ?? Versions[1];
        return new JsonObject
        {
            ["protocolVersion"] = Versions.Contains(asked) ? asked : Versions[1],
            ["capabilities"] = new JsonObject { ["tools"] = new JsonObject { ["listChanged"] = false } },
            ["serverInfo"] = new JsonObject { ["name"] = "hopdrop", ["title"] = "HopDrop", ["version"] = AgentBridge.Version },
            ["instructions"] = "HopDrop moves files between this PC and the user's own paired phones and PCs over the local network (encrypted, no cloud). "
                + "Use list_devices to see devices, send_files to send files or whole folders to one, and wait_for_files or recent_transfers to find files the user sent to this PC. "
                + "Devices must be paired by the user in HopDrop first, and must be online (HopDrop open on them, same Wi-Fi or hotspot)."
        };
    }

    private static JsonArray Tools() =>
    [
        Tool("list_devices", "List paired devices",
            "List the devices paired with HopDrop on this PC (the user's phones and other PCs), whether each is online now, and the folder where files sent to this PC are saved. Call this first to get device names for send_files.",
            new JsonObject(), [], readOnly: true),
        Tool("send_files", "Send files to a device",
            "Send files and/or whole folders from this PC to one of the user's paired devices over the local network. Folders arrive as folders, with their subfolders. "
            + "Waits up to wait_seconds for the transfer to finish; if it's still running, returns status \"sending\" and a transferId for transfer_status. "
            + "The device must be online (HopDrop open on it, on the same network). Large transfers can take minutes.",
            new JsonObject
            {
                ["device"] = Prop("string", "Device name (or id) from list_devices. Can be left out when only one device is paired."),
                ["paths"] = new JsonObject { ["type"] = "array", ["minItems"] = 1, ["items"] = new JsonObject { ["type"] = "string" }, ["description"] = "Full paths of files or folders on this PC, for example C:\\Users\\me\\Documents\\report.pdf." },
                ["wait_seconds"] = Prop("integer", "How long to wait for the transfer to finish before returning (0–600, default 50).", 0, 600)
            }, ["paths"], readOnly: false),
        Tool("transfer_status", "Check a transfer",
            "Progress or result of a transfer started by send_files.",
            new JsonObject { ["transfer_id"] = Prop("string", "The transferId returned by send_files.") }, ["transfer_id"], readOnly: true),
        Tool("recent_transfers", "Recent transfers",
            "HopDrop's activity list, newest first: what arrived on this PC (with the full paths where the files were saved) and what was sent from it.",
            new JsonObject
            {
                ["limit"] = Prop("integer", "How many transfers to return (1–50, default 10).", 1, 50),
                ["direction"] = new JsonObject { ["type"] = "string", ["enum"] = new JsonArray("received", "sent", "all"), ["description"] = "Only received, only sent, or all (default)." }
            }, [], readOnly: true),
        Tool("wait_for_files", "Wait for files from a device",
            "Wait until one of the user's devices sends files to this PC, then return the full paths where they were saved. Use it right after asking the user to send something from their phone. "
            + "Files that arrived before this call are in recent_transfers.",
            new JsonObject
            {
                ["timeout_seconds"] = Prop("integer", "How long to wait (1–900, default 50). If nothing arrives, call again to keep waiting.", 1, 900),
                ["device"] = Prop("string", "Only wait for this device (name or id). Leave out to accept any paired device.")
            }, [], readOnly: true)
    ];

    private static JsonObject Tool(string name, string title, string description, JsonObject properties, string[] required, bool readOnly) => new()
    {
        ["name"] = name, ["title"] = title, ["description"] = description,
        ["inputSchema"] = new JsonObject
        {
            ["type"] = "object", ["properties"] = properties, ["additionalProperties"] = false,
            ["required"] = new JsonArray(required.Select(r => (JsonNode?)r).ToArray())
        },
        ["annotations"] = new JsonObject { ["title"] = title, ["readOnlyHint"] = readOnly, ["destructiveHint"] = false, ["openWorldHint"] = false }
    };

    private static JsonObject Prop(string type, string description, int? minimum = null, int? maximum = null)
    {
        var prop = new JsonObject { ["type"] = type, ["description"] = description };
        if (minimum is not null) prop["minimum"] = minimum;
        if (maximum is not null) prop["maximum"] = maximum;
        return prop;
    }

    private static async Task<JsonObject> CallToolAsync(string dataDirectory, JsonObject? parameters)
    {
        string name = parameters?["name"]?.GetValue<string>() ?? "";
        var args = parameters?["arguments"] as JsonObject ?? [];
        JsonObject request;
        int wait = 0;
        try
        {
            switch (name)
            {
                case "list_devices": request = new() { ["op"] = "devices" }; break;
                case "send_files":
                    wait = Math.Clamp(Int(args, "wait_seconds") ?? 50, 0, 600);
                    request = new()
                    {
                        ["op"] = "send", ["device"] = Str(args, "device"), ["wait"] = wait,
                        // Relative paths mean the agent's folder, which is this process's working folder, not HopDrop's.
                        ["paths"] = new JsonArray((args["paths"] as JsonArray ?? []).Select(p => (JsonNode?)Path.GetFullPath(p?.GetValue<string>() ?? ".")).ToArray())
                    };
                    break;
                case "transfer_status": request = new() { ["op"] = "status", ["id"] = Str(args, "transfer_id") }; break;
                case "recent_transfers": request = new() { ["op"] = "recent", ["limit"] = Int(args, "limit"), ["direction"] = Str(args, "direction") }; break;
                case "wait_for_files":
                    wait = Math.Clamp(Int(args, "timeout_seconds") ?? 50, 1, 900);
                    request = new() { ["op"] = "wait", ["timeout"] = wait, ["device"] = Str(args, "device") };
                    break;
                default: return Text("Unknown tool: " + name, true);
            }
            var reply = await AgentClient.CallAsync(dataDirectory, request, TimeSpan.FromSeconds(wait + 60));
            if (reply["ok"]?.GetValue<bool>() != true) return Text(reply["error"]?.GetValue<string>() ?? "HopDrop couldn't do that.", true);
            reply.Remove("ok");
            return Text(reply.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }), false);
        }
        catch (AgentException e) { return Text(e.Message, true); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException or InvalidOperationException or FormatException)
        { return Text("HopDrop couldn't do that: " + e.Message, true); }
    }

    private static string? Str(JsonObject args, string key) => args[key] is JsonValue v && v.TryGetValue(out string? s) ? s : null;
    private static int? Int(JsonObject args, string key) => args[key] is JsonValue v ? v.TryGetValue(out int i) ? i : v.TryGetValue(out double d) ? (int)d : null : null;

    private static JsonObject Text(string text, bool error) =>
        new() { ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = text }), ["isError"] = error };
    private static JsonObject Result(JsonNode? id, JsonObject result) => new() { ["jsonrpc"] = "2.0", ["id"] = id, ["result"] = result };
    private static JsonObject Error(JsonNode? id, int code, string message) =>
        new() { ["jsonrpc"] = "2.0", ["id"] = id, ["error"] = new JsonObject { ["code"] = code, ["message"] = message } };

    private static void Write(JsonObject message)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(message.ToJsonString() + "\n");
        WriteLock.Wait();
        try { _out.Write(bytes); _out.Flush(); }
        catch (IOException) { }
        finally { WriteLock.Release(); }
    }
}

/// <summary>"HopDrop.exe agent …": the same abilities as the MCP tools, for scripts and agents without MCP. Prints JSON.</summary>
internal static class AgentCli
{
    private const string Usage = """
        HopDrop for scripts and AI agents (turn on HopDrop → Settings → AI agents first). Prints JSON.

          HopDrop.exe agent devices
          HopDrop.exe agent send [--to <device>] [--wait <seconds>] <file or folder>...
          HopDrop.exe agent status <transfer id>
          HopDrop.exe agent recent [--limit <n>] [--direction received|sent|all]
          HopDrop.exe agent wait [--timeout <seconds>] [--from <device>]

        Add --data <folder> to use a HopDrop that runs with that data folder.
        """;

    [DllImport("kernel32.dll")] private static extern bool AttachConsole(int processId);
    [DllImport("kernel32.dll")] private static extern IntPtr GetStdHandle(int handle);

    public static int Run(string[] args, string dataDirectory)
    {
        // HopDrop.exe is a windowed app: in a terminal without redirection, borrow the terminal's console to print.
        if (GetStdHandle(-11) == IntPtr.Zero) AttachConsole(-1);
        var rest = args.Skip(1).ToList();
        int data = rest.IndexOf("--data");
        if (data >= 0) rest.RemoveRange(data, Math.Min(2, rest.Count - data)); // already read by Program
        string command = rest.Count > 0 ? rest[0] : "help";
        rest = rest.Skip(1).ToList();
        try
        {
            string? Option(string name)
            {
                int i = rest.IndexOf(name);
                if (i < 0) return null;
                if (i + 1 >= rest.Count) throw new AgentException($"{name} needs a value.");
                string value = rest[i + 1]; rest.RemoveRange(i, 2); return value;
            }
            int? Number(string name) => Option(name) is { } text ? int.TryParse(text, out int n) ? n : throw new AgentException($"{name} needs a number.") : null;
            JsonObject request; int wait = 0;
            switch (command)
            {
                case "devices": request = new() { ["op"] = "devices" }; break;
                case "send":
                    string? to = Option("--to"); wait = Math.Clamp(Number("--wait") ?? 600, 0, 600);
                    if (rest.Count == 0) throw new AgentException("Name at least one file or folder to send.");
                    request = new() { ["op"] = "send", ["device"] = to, ["wait"] = wait, ["paths"] = new JsonArray(rest.Select(p => (JsonNode?)Path.GetFullPath(p)).ToArray()) };
                    break;
                case "status": request = new() { ["op"] = "status", ["id"] = rest.FirstOrDefault() }; break;
                case "recent": request = new() { ["op"] = "recent", ["limit"] = Number("--limit"), ["direction"] = Option("--direction") }; break;
                case "wait":
                    string? from = Option("--from"); wait = Math.Clamp(Number("--timeout") ?? 120, 1, 900);
                    request = new() { ["op"] = "wait", ["timeout"] = wait, ["device"] = from };
                    break;
                default: Console.Out.WriteLine(Usage); return command is "help" or "--help" or "-h" ? 0 : 2;
            }
            var reply = AgentClient.CallAsync(dataDirectory, request, TimeSpan.FromSeconds(wait + 60)).GetAwaiter().GetResult();
            Console.Out.WriteLine(reply.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
            return reply["ok"]?.GetValue<bool>() == true ? 0 : 1;
        }
        catch (AgentException e)
        {
            Console.Out.WriteLine(new JsonObject { ["ok"] = false, ["error"] = e.Message }.ToJsonString());
            return 1;
        }
    }
}
