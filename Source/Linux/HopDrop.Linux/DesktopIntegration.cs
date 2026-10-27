using System.Diagnostics;
using System.Text;
using HopDrop.Core;

namespace HopDrop.Linux;

/// <summary>Per-user desktop files. Only the files created by HopDrop are changed.</summary>
internal sealed class DesktopIntegration
{
    private readonly string _config;
    private readonly string _data;
    private readonly IReadOnlyList<string> _command;
    internal DesktopIntegration(string? profile = null, IReadOnlyList<string>? command = null)
    {
        string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        _config = profile is null ? Xdg("XDG_CONFIG_HOME", Path.Combine(home, ".config")) : Path.Combine(profile, "desktop", "config");
        _data = profile is null ? Xdg("XDG_DATA_HOME", Path.Combine(home, ".local", "share")) : Path.Combine(profile, "desktop", "data");
        _command = command ?? HostCommand(profile);
    }
    private static string Xdg(string name, string fallback) => Environment.GetEnvironmentVariable(name) is { } value && Path.IsPathFullyQualified(value) ? value : fallback;
    internal static string[] HostCommand(string? profile)
    {
        if (profile is not null && Path.GetFullPath(profile) == Path.GetFullPath(HopDropPaths.Default.DataDirectory)) profile = null;
        var args = new List<string> { Environment.ProcessPath ?? throw new InvalidOperationException("Cannot locate HopDrop.") };
        if (Path.GetFileNameWithoutExtension(args[0]).Equals("dotnet", StringComparison.OrdinalIgnoreCase))
            args.Add(typeof(Program).Assembly.Location);
        if (profile is not null) { args.Add("--data"); args.Add(Path.GetFullPath(profile)); }
        return args.ToArray();
    }
    internal static Task LaunchHostAsync(string profile)
    {
        var command = HostCommand(profile);
        // Keep the GUI independent of the CLI/MCP protocol streams. Paths remain separate arguments.
        var start = new ProcessStartInfo("/bin/sh") { UseShellExecute = false, CreateNoWindow = true };
        start.ArgumentList.Add("-c");
        start.ArgumentList.Add("exec \"$@\" </dev/null >/dev/null 2>&1");
        start.ArgumentList.Add("hopdrop-launch");
        foreach (string arg in command) start.ArgumentList.Add(arg);
        start.ArgumentList.Add("--minimized");
        _ = Process.Start(start) ?? throw new IOException("Cannot start HopDrop.");
        return Task.CompletedTask;
    }
    internal static string Quote(string argument)
    {
        if (argument.Any(c => c is '\n' or '\r' or '\0')) throw new ArgumentException("Invalid desktop argument.");
        var escaped = new StringBuilder("\"");
        foreach (char c in argument)
        {
            if (c is '\\' or '"' or '`' or '$') escaped.Append('\\');
            escaped.Append(c == '%' ? "%%" : c);
        }
        // Desktop-entry strings decode escapes before the Exec field is parsed.
        return (escaped.Append('"').ToString()).Replace("\\", "\\\\");
    }
    private string Command => string.Join(" ", _command.Select(Quote));
    internal string AutostartPath => Path.Combine(_config, "autostart", "hopdrop.desktop");
    internal string NemoPath => Path.Combine(_data, "nemo", "actions", "hopdrop.nemo_action");
    internal string NautilusPath => Path.Combine(_data, "nautilus", "scripts", "Send with HopDrop");
    internal string DolphinPath => Path.Combine(_data, "kio", "servicemenus", "hopdrop.desktop");
    internal void SetAutostart(bool enabled, bool background)
    {
        if (!enabled) { File.Delete(AutostartPath); return; }
        Write(AutostartPath, "[Desktop Entry]\nType=Application\nName=HopDrop\nIcon=hopdrop\nTerminal=false\nExec=" + Command + (background ? " --background\n" : " --minimized\n"));
    }
    internal void SetFileManager(bool enabled)
    {
        if (!enabled) { File.Delete(NemoPath); File.Delete(NautilusPath); File.Delete(DolphinPath); return; }
        Write(NemoPath, "[Nemo Action]\nName=Send with HopDrop\nName[ar]=إرسال عبر HopDrop\nComment=Send selected files or folders\nIcon-Name=hopdrop\nExec=" + Command + " --send %F\nSelection=notnone\nExtensions=any;\nQuote=double\nTerminal=false\nUriScheme=file\n");
        // Nautilus supplies each selection as a distinct argument; no shell evaluation of filenames.
        string shellCommand = string.Join(" ", _command.Select(a => "'" + a.Replace("'", "'\"'\"'") + "'"));
        Write(NautilusPath, "#!/bin/sh\nexec " + shellCommand + " --send \"$@\"\n");
        if (OperatingSystem.IsLinux()) File.SetUnixFileMode(NautilusPath, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        Write(DolphinPath, "[Desktop Entry]\nType=Service\nMimeType=application/octet-stream;inode/directory;\nActions=hopdrop;\nX-KDE-Priority=TopLevel\n[Desktop Action hopdrop]\nName=Send with HopDrop\nName[ar]=إرسال عبر HopDrop\nIcon=hopdrop\nExec=" + Command + " --send %F\n");
    }
    private static void Write(string path, string contents)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, contents, new UTF8Encoding(false));
    }
}
