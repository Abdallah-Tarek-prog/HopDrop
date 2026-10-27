using Avalonia;
using HopDrop.Agents;
using HopDrop.Core;

namespace HopDrop.Linux;

internal static class Program
{
    internal static string[] Arguments { get; private set; } = [];
    [STAThread]
    public static int Main(string[] args)
    {
        Arguments = args;
        string data = Option("--data") is { } directory ? Path.GetFullPath(directory) : HopDropPaths.Default.DataDirectory;
        if (args.Contains("--mcp")) return McpServer.Run(data, DesktopIntegration.LaunchHostAsync);
        if (args.FirstOrDefault() == "agent") return AgentCli.Run(args, data, DesktopIntegration.LaunchHostAsync);
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        return 0;
    }
    internal static string? Option(string name) => Arguments.SkipWhile(a => a != name).Skip(1).FirstOrDefault();
    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>().UsePlatformDetect().LogToTrace();
}
