using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using HopDrop.Agents;
using HopDrop.Core;

if (args is ["--probe", var dataDirectory])
{
    try
    {
        var response = await AgentClient.CallAsync(dataDirectory, new JsonObject { ["op"] = "hello" }, TimeSpan.FromSeconds(4),
            _ => throw new InvalidOperationException("launch disabled for permission probe"));
        Console.WriteLine("CONNECTED: " + response.ToJsonString());
    }
    catch (Exception e)
    {
        Console.WriteLine("DENIED: " + e.GetType().Name + ": " + e.Message);
        Environment.ExitCode = 3;
    }
    return;
}

string root = Path.Combine(Path.GetTempPath(), "HopDrop-agent-test-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
try
{
    static void Check(bool good, string message) { if (!good) throw new Exception(message); }
    static HopDropPaths Paths(string root) => new(Path.Combine(root, "data"), Path.Combine(root, "logs"), Path.Combine(root, "receive"));
    string senderRoot = Path.Combine(root, "sender"), receiverRoot = Path.Combine(root, "receiver");
    await using var sender = await HopDropPeer.OpenAsync(new PeerOptions(Paths(senderRoot), 0, "Agent PC"));
    await using var receiver = await HopDropPeer.OpenAsync(new PeerOptions(Paths(receiverRoot), 0, "Test phone"));
    await receiver.StartAsync();
    await sender.PairQrAsync(receiver.CreateQrUri([IPAddress.Loopback]));
    await sender.StartAsync();
    bool enabled = false;
    await using var service = new AgentService(sender, Paths(senderRoot).DataDirectory, () => enabled);
    using var stop = new CancellationTokenSource();
    service.Start(stop.Token);
    Task Launch(string _) => throw new Exception("The active host should be reused.");
    async Task<JsonObject> Call(JsonObject request, int seconds = 20) => await AgentClient.CallAsync(Paths(senderRoot).DataDirectory, request, TimeSpan.FromSeconds(seconds), Launch);

    var denied = await Call(new() { ["op"] = "devices" });
    Check(denied["ok"]?.GetValue<bool>() == false && denied["error"]?.GetValue<string>()?.Contains("off") == true, "disabled opt-in");
    var hello = await Call(new() { ["op"] = "hello" });
    Check(hello["enabled"]?.GetValue<bool>() == false, "hello opt-in");
    if (OperatingSystem.IsLinux() && Environment.UserName == "root")
    {
        var probe = new ProcessStartInfo("runuser") { RedirectStandardOutput = true, RedirectStandardError = true };
        probe.ArgumentList.Add("-u"); probe.ArgumentList.Add("nobody"); probe.ArgumentList.Add("--");
        probe.ArgumentList.Add(Environment.ProcessPath!);
        probe.ArgumentList.Add("--probe"); probe.ArgumentList.Add(Paths(senderRoot).DataDirectory);
        using var child = Process.Start(probe)!;
        string result = await child.StandardOutput.ReadToEndAsync();
        await child.WaitForExitAsync();
        Check(child.ExitCode == 3 && result.StartsWith("DENIED:"), "other user cannot connect to agent pipe: " + result);
        Console.WriteLine("PASS: cross-user pipe probe " + result.Trim());
    }
    enabled = true;
    var devices = await Call(new() { ["op"] = "devices" });
    Check(devices["ok"]?.GetValue<bool>() == true && devices["devices"]?.AsArray().Count == 1, "list devices");

    string loose = Path.Combine(root, "loose.bin");
    byte[] looseBytes = RandomNumberGenerator.GetBytes(32_777);
    await File.WriteAllBytesAsync(loose, looseBytes);
    string folder = Path.Combine(root, "Folder"), nested = Path.Combine(folder, "Sub");
    Directory.CreateDirectory(nested);
    string nestedFile = Path.Combine(nested, "nested.txt");
    byte[] nestedBytes = RandomNumberGenerator.GetBytes(12_345);
    await File.WriteAllBytesAsync(nestedFile, nestedBytes);
    var sent = await Call(new() { ["op"] = "send", ["device"] = "Test phone", ["wait"] = 20,
        ["paths"] = new JsonArray(loose, folder) }, 30);
    Check(sent["status"]?.GetValue<string>() == "sent" && sent["files"]?.GetValue<int>() == 2, "file/folder send");
    Check(File.ReadAllBytes(Path.Combine(receiver.ReceiveDirectory, "loose.bin")).SequenceEqual(looseBytes), "loose bytes");
    Check(File.ReadAllBytes(Path.Combine(receiver.ReceiveDirectory, "Folder", "Sub", "nested.txt")).SequenceEqual(nestedBytes), "folder bytes");
    var status = await Call(new() { ["op"] = "status", ["id"] = sent["transferId"]?.GetValue<string>() });
    Check(status["status"]?.GetValue<string>() == "sent", "transfer status");
    var history = await Call(new() { ["op"] = "recent", ["direction"] = "sent" });
    Check(history["transfers"]?.AsArray().Any(h => h?["transport"]?.GetValue<string>() == TransferTransport.LocalNetwork) == true, "history transport");

    var waiting = Call(new() { ["op"] = "wait", ["timeout"] = 10, ["device"] = "Test phone" }, 15);
    await Task.Delay(500);
    string reverse = Path.Combine(root, "reverse.txt");
    await File.WriteAllTextAsync(reverse, "agent wait works");
    await receiver.SendAsync(sender.Id, [reverse]);
    var arrived = await waiting;
    Check(arrived["status"]?.GetValue<string>() == "received", "wait result");
    Check(arrived["files"]?.AsArray().Any(f => File.ReadAllText(f!.GetValue<string>()) == "agent wait works") == true, "wait file bytes");

    var missing = await Call(new() { ["op"] = "send", ["paths"] = new JsonArray(Path.Combine(root, "missing")) });
    Check(missing["ok"]?.GetValue<bool>() == false, "missing file error");
    var unknown = await Call(new() { ["op"] = "send", ["device"] = "unknown", ["paths"] = new JsonArray(loose) });
    Check(unknown["ok"]?.GetValue<bool>() == false, "unknown device error");
    var store = new JsonStore<List<PairedDevice>>(Paths(senderRoot).DevicesPath, []);
    await store.UpdateAsync(list => { list.Add(new PairedDevice(new string('a', 64), "Test tablet", "android", DateTimeOffset.UtcNow, [], DateTimeOffset.UtcNow)); return list; });
    var ambiguous = await Call(new() { ["op"] = "send", ["device"] = "Test", ["paths"] = new JsonArray(loose) });
    Check(ambiguous["ok"]?.GetValue<bool>() == false && ambiguous["error"]?.GetValue<string>()?.Contains("several") == true, "ambiguous device error");
    var offline = await Call(new() { ["op"] = "send", ["device"] = "Test tablet", ["paths"] = new JsonArray(loose), ["wait"] = 5 });
    Check(offline["status"]?.GetValue<string>() == "failed", "offline device is never reported sent");

    string mcpInput = string.Join('\n',
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_devices\",\"arguments\":{}}}", "");
    using var output = new StringWriter();
    Check(McpServer.Run(Paths(senderRoot).DataDirectory, Launch, new StringReader(mcpInput), output) == 0, "MCP exit");
    var replies = output.ToString().Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(line => JsonNode.Parse(line)).ToList();
    Check(replies.Count == 3 && replies.Any(r => r?["id"]?.GetValue<int>() == 1 && r?["result"]?["protocolVersion"]?.GetValue<string>() == "2025-11-25"), "MCP initialize");
    Check(replies.Any(r => r?["id"]?.GetValue<int>() == 2 && r?["result"]?["tools"]?.AsArray().Count == 5), "MCP tools/list");
    Check(replies.Any(r => r?["id"]?.GetValue<int>() == 3 && r?["result"]?["isError"]?.GetValue<bool>() == false), "MCP tools/call");
    static string McpCall(int id, string name, JsonObject args) => new JsonObject
    {
        ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = "tools/call",
        ["params"] = new JsonObject { ["name"] = name, ["arguments"] = args }
    }.ToJsonString();
    string mcpFile = Path.Combine(root, "mcp.txt");
    await File.WriteAllTextAsync(mcpFile, "MCP sent this");
    using var sendOutput = new StringWriter();
    McpServer.Run(Paths(senderRoot).DataDirectory, Launch, new StringReader(McpCall(4, "send_files", new()
    { ["device"] = "Test phone", ["paths"] = new JsonArray(mcpFile), ["wait_seconds"] = 20 }) + "\n"), sendOutput);
    var mcpSent = JsonNode.Parse(sendOutput.ToString())!;
    Check(mcpSent["result"]?["isError"]?.GetValue<bool>() == false, "MCP send_files");
    var mcpSentBody = JsonNode.Parse(mcpSent["result"]?["content"]?[0]?["text"]?.GetValue<string>() ?? "")!;
    Check(mcpSentBody["status"]?.GetValue<string>() == "sent" && File.ReadAllText(Path.Combine(receiver.ReceiveDirectory, "mcp.txt")) == "MCP sent this", "MCP send bytes");
    string mcpTransferId = mcpSentBody["transferId"]!.GetValue<string>();
    using var otherOutput = new StringWriter();
    string otherInput = string.Join('\n',
        McpCall(5, "transfer_status", new() { ["transfer_id"] = mcpTransferId }),
        McpCall(6, "recent_transfers", new() { ["direction"] = "sent" }),
        McpCall(7, "wait_for_files", new() { ["timeout_seconds"] = 1 }),
        McpCall(8, "send_files", new() { ["device"] = "Test phone", ["paths"] = new JsonArray(Path.Combine(root, "absent")) }), "");
    McpServer.Run(Paths(senderRoot).DataDirectory, Launch, new StringReader(otherInput), otherOutput);
    var other = otherOutput.ToString().Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(line => JsonNode.Parse(line)!).ToList();
    string Body(int id) => other.Single(r => r["id"]?.GetValue<int>() == id)["result"]?["content"]?[0]?["text"]?.GetValue<string>() ?? "";
    Check(JsonNode.Parse(Body(5))?["status"]?.GetValue<string>() == "sent", "MCP transfer_status");
    Check(JsonNode.Parse(Body(6))?["transfers"]?.AsArray().Count > 0, "MCP recent_transfers");
    Check(JsonNode.Parse(Body(7))?["status"]?.GetValue<string>() == "nothing yet", "MCP wait_for_files");
    Check(other.Single(r => r["id"]?.GetValue<int>() == 8)["result"]?["isError"]?.GetValue<bool>() == true && Body(8).Contains("Not found"), "MCP invalid path error");
    using var cliOutput = new StringWriter();
    TextWriter previousOutput = Console.Out;
    Console.SetOut(cliOutput);
    int cliCode;
    try { cliCode = AgentCli.Run(["agent", "status", mcpTransferId], Paths(senderRoot).DataDirectory, Launch); }
    finally { Console.SetOut(previousOutput); }
    Check(cliCode == 0 && JsonNode.Parse(cliOutput.ToString())?["status"]?.GetValue<string>() == "sent", "CLI status");
    enabled = false;
    var deniedAgain = await Call(new() { ["op"] = "status", ["id"] = sent["transferId"]?.GetValue<string>() });
    Check(deniedAgain["ok"]?.GetValue<bool>() == false, "revoked access");
    Console.WriteLine("PASS: disabled/revoked, devices, real file and folder bytes, status, transport history, wait, missing/unknown/ambiguous/offline, CLI, MCP initialize/list/all tools/errors");
    stop.Cancel();
}
finally
{
    try { Directory.Delete(root, true); } catch (IOException) { }
}
