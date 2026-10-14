using System.Runtime.CompilerServices;

namespace HopDrop.Tests;

internal static class Scratch
{
    private static readonly string Run = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.test-scratch/run"));

    [ModuleInitializer]
    internal static void StartRun()
    {
        if (Directory.Exists(Run)) Directory.Delete(Run, true);
        Directory.CreateDirectory(Run);
    }

    internal static string NewFolder()
    {
        string path = Path.Combine(Run, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }
}
