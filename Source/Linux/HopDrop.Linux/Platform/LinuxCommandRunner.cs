using System.Diagnostics;

namespace HopDrop.Linux.Platform;

internal sealed record CommandResult(int ExitCode, string Output, string Error)
{
    public bool Ok => ExitCode == 0;
}

// This small seam lets hardware and policy operations be tested without touching the host network.
internal interface ILinuxCommandRunner
{
    Task<CommandResult> RunAsync(string file, IReadOnlyList<string> args, CancellationToken stop);
}

internal sealed class LinuxCommandRunner : ILinuxCommandRunner
{
    public async Task<CommandResult> RunAsync(string file, IReadOnlyList<string> args, CancellationToken stop)
    {
        var start = new ProcessStartInfo(file)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        start.Environment["LC_ALL"] = "C"; // System output is parsed; the user's UI language stays independent.
        start.Environment["LANG"] = "C";
        foreach (string arg in args) start.ArgumentList.Add(arg);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop);
        timeout.CancelAfter(TimeSpan.FromSeconds(60));
        using var process = Process.Start(start) ?? throw new IOException($"Could not launch {file}.");
        Task<string> output = process.StandardOutput.ReadToEndAsync(timeout.Token);
        Task<string> error = process.StandardError.ReadToEndAsync(timeout.Token);
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            if (!process.HasExited)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { /* It exited during cancellation. */ }
                await process.WaitForExitAsync(CancellationToken.None);
            }
            if (stop.IsCancellationRequested) throw;
            return new(124, "", "The system command timed out.");
        }
        return new(process.ExitCode, await output, await error);
    }
}
