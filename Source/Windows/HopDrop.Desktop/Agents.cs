using System.Diagnostics;
using System.Runtime.InteropServices;
using HopDrop.Core;

namespace HopDrop.Desktop;

internal static class AgentBridge
{
    private static HopDrop.Agents.AgentService? _service;

    public static void Start(HopDropPeer peer, string dataDirectory, CancellationToken stop)
    {
        _service = new HopDrop.Agents.AgentService(peer, dataDirectory, () => Preferences.AgentsEnabled);
        _service.Start(stop);
    }
}

internal static class AgentHost
{
    public static Task LaunchAsync(string dataDirectory)
    {
        string exe = Store.Packaged ? Store.Alias : Environment.ProcessPath ?? throw new InvalidOperationException("HopDrop.exe couldn't be found.");
        var start = new ProcessStartInfo(exe) { UseShellExecute = true, WindowStyle = ProcessWindowStyle.Minimized };
        start.ArgumentList.Add("--minimized");
        start.ArgumentList.Add("--data");
        start.ArgumentList.Add(dataDirectory);
        Process.Start(start);
        return Task.CompletedTask;
    }
}

internal static class McpServer
{
    public static int Run(string dataDirectory) => HopDrop.Agents.McpServer.Run(dataDirectory, AgentHost.LaunchAsync);
}

internal static class AgentCli
{
    [DllImport("kernel32.dll")] private static extern bool AttachConsole(int processId);
    [DllImport("kernel32.dll")] private static extern IntPtr GetStdHandle(int handle);

    public static int Run(string[] args, string dataDirectory)
    {
        if (GetStdHandle(-11) == IntPtr.Zero) AttachConsole(-1);
        return HopDrop.Agents.AgentCli.Run(args, dataDirectory, AgentHost.LaunchAsync);
    }
}
