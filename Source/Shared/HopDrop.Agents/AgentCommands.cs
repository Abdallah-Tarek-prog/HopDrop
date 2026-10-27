using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace HopDrop.Agents;

public static class AgentClient
{
    public static async Task<JsonObject> CallAsync(string dataDirectory, JsonObject request, TimeSpan timeout, Func<string, Task> launchHost)
    {
        await using var pipe = await ConnectAsync(dataDirectory, launchHost);
        byte[] bytes = Encoding.UTF8.GetBytes(request.ToJsonString() + "\n");
        using var stop = new CancellationTokenSource(timeout);
        await pipe.WriteAsync(bytes, stop.Token);
        await pipe.FlushAsync(stop.Token);
        using var reader = new StreamReader(pipe, new UTF8Encoding(false));
        string? line;
        try { line = await AgentService.ReadLineAsync(reader, 4 * 1024 * 1024, stop.Token); }
        catch (OperationCanceledException) { throw new AgentException("HopDrop didn't answer in time."); }
        return JsonNode.Parse(line ?? throw new AgentException("HopDrop closed the connection."))?.AsObject() ?? throw new AgentException("HopDrop sent an empty answer.");
    }

    private static async Task<NamedPipeClientStream> ConnectAsync(string dataDirectory, Func<string, Task> launchHost)
    {
        string name = AgentService.PipeName(dataDirectory);
        var pipe = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        try { await pipe.ConnectAsync(1500); return pipe; }
        catch (TimeoutException) { await pipe.DisposeAsync(); }
        try { await launchHost(Path.GetFullPath(dataDirectory)); }
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
public static class McpServer
{
    private static readonly string[] Versions = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];

    public static int Run(string dataDirectory, Func<string, Task> launchHost)
    {
        using var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        using var output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
        return Run(dataDirectory, launchHost, input, output);
    }

    public static int Run(string dataDirectory, Func<string, Task> launchHost, TextReader input, TextWriter output)
    {
        var writeLock = new object();
        void Write(JsonObject message)
        {
            lock (writeLock)
            {
                try { output.WriteLine(message.ToJsonString()); output.Flush(); }
                catch (IOException) { }
            }
        }
        var running = new List<Task>();
        while (true)
        {
            string? line;
            try { line = AgentService.ReadLineAsync(input, 64 * 1024, CancellationToken.None).GetAwaiter().GetResult(); }
            catch (AgentException) { Write(Error(null, -32700, "Parse error: message too large")); break; }
            if (line is null) break;
            if (string.IsNullOrWhiteSpace(line)) continue;
            JsonObject message;
            try { message = JsonNode.Parse(line)?.AsObject() ?? throw new JsonException(); }
            catch (Exception e) when (e is JsonException or InvalidOperationException) { Write(Error(null, -32700, "Parse error")); continue; }
            if (message["method"]?.GetValue<string>() is not { } method || !message.ContainsKey("id")) continue; // notifications, and replies we never asked for
            JsonNode? id = message["id"]?.DeepClone();
            JsonObject? parameters = message["params"] as JsonObject;
            running.RemoveAll(t => t.IsCompleted);
            // Requests run side by side: a long wait_for_files mustn't block pings or other tools.
            running.Add(Task.Run(async () => Write(await AnswerAsync(dataDirectory, id, method, parameters, launchHost))));
        }
        Task.WaitAll([.. running]);
        return 0;
    }

    private static async Task<JsonObject> AnswerAsync(string dataDirectory, JsonNode? id, string method, JsonObject? parameters, Func<string, Task> launchHost)
    {
        try
        {
            return method switch
            {
                "initialize" => Result(id, Initialize(parameters)),
                "ping" => Result(id, new JsonObject()),
                "tools/list" => Result(id, new JsonObject { ["tools"] = Tools() }),
                "tools/call" => Result(id, await CallToolAsync(dataDirectory, parameters, launchHost)),
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
            ["serverInfo"] = new JsonObject { ["name"] = "hopdrop", ["title"] = "HopDrop", ["version"] = AgentService.Version },
            ["instructions"] = "HopDrop moves files between this PC and the user's own paired phones and PCs over the local network (encrypted, no cloud). "
                + "Use list_devices to see devices, send_files to send files or whole folders to one, and wait_for_files or recent_transfers to find files the user sent to this PC. "
                + "Send only the files or folders the user asked to send, to the requested paired recipient. "
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
                ["paths"] = new JsonObject { ["type"] = "array", ["minItems"] = 1, ["items"] = new JsonObject { ["type"] = "string" }, ["description"] = "Full paths of files or folders on this PC, for example /home/me/report.pdf on Linux or C:\\Users\\me\\Documents\\report.pdf on Windows." },
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

    private static async Task<JsonObject> CallToolAsync(string dataDirectory, JsonObject? parameters, Func<string, Task> launchHost)
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
                        ["paths"] = new JsonArray((args["paths"] as JsonArray ?? []).Select(p => (JsonNode?)Path.GetFullPath(p?.GetValue<string>() ?? "")).ToArray())
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
            var reply = await AgentClient.CallAsync(dataDirectory, request, TimeSpan.FromSeconds(wait + 60), launchHost);
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

}

/// <summary>"HopDrop.exe agent …": the same abilities as the MCP tools, for scripts and agents without MCP. Prints JSON.</summary>
public static class AgentCli
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

    public static int Run(string[] args, string dataDirectory, Func<string, Task> launchHost)
    {
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
                default: Console.Out.WriteLine(OperatingSystem.IsLinux() ? Usage.Replace("HopDrop.exe", "HopDrop.Linux") : Usage); return command is "help" or "--help" or "-h" ? 0 : 2;
            }
            var reply = AgentClient.CallAsync(dataDirectory, request, TimeSpan.FromSeconds(wait + 60), launchHost).GetAwaiter().GetResult();
            Console.Out.WriteLine(reply.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
            return reply["ok"]?.GetValue<bool>() == true ? 0 : 1;
        }
        catch (AgentException e)
        {
            Console.Out.WriteLine(new JsonObject { ["ok"] = false, ["error"] = e.Message }.ToJsonString());
            return 1;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            Console.Out.WriteLine(new JsonObject { ["ok"] = false, ["error"] = e.Message }.ToJsonString());
            return 1;
        }
    }
}
