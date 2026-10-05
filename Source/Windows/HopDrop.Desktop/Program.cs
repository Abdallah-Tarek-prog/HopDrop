using HopDrop.Core;
using Velopack;

namespace HopDrop.Desktop;

public static class Program
{
    /// <summary>The command line, plus what the Store package was started for (Share menu, sign-in); see <see cref="Store"/>.</summary>
    internal static string[] Arguments { get; private set; } = [];

    [STAThread]
    public static int Main(string[] args)
    {
        // AI agents start "HopDrop.exe --mcp" (MCP over standard input/output) or "HopDrop.exe agent …": they get answers
        // from the running HopDrop, with no window of their own.
        bool agent = args.Contains("--mcp") || args.Length > 0 && args[0] == "agent";
        // Must run first: when Setup, an update or the uninstaller starts HopDrop to run a hook, this handles it and exits.
        // An update that finished downloading is applied here, on the next start (never under an agent: the restart would cut it off).
        VelopackApp.Build()
            .SetAutoApplyOnStartup(!agent)
            .OnBeforeUninstallFastCallback(_ => Platform.RemoveFromWindows())
            .Run();
        if (agent)
        {
            int data = Array.IndexOf(args, "--data");
            string folder = Path.GetFullPath(data >= 0 && data + 1 < args.Length ? args[data + 1] : HopDropPaths.Default.DataDirectory);
            return args.Contains("--mcp") ? McpServer.Run(folder) : AgentCli.Run(args, folder);
        }
        Arguments = Store.Arguments(args);
        var app = new App();
        app.InitializeComponent();
        app.Run();
        return 0;
    }
}
