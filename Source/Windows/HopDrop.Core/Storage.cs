using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace HopDrop.Core;

public sealed record HopDropPaths(string DataDirectory, string LogDirectory, string? ReceiveDirectory = null)
{
    public string? SettingsFile { get; init; }
    public string? DevicesFile { get; init; }
    public string? HistoryFile { get; init; }
    public string? IdentityPfxFile { get; init; }
    public string? IdentityKeyFile { get; init; }
    public string SettingsPath => SettingsFile ?? Path.Combine(DataDirectory, "settings.json");
    public string DevicesPath => DevicesFile ?? Path.Combine(DataDirectory, "devices.json");
    public string HistoryPath => HistoryFile ?? Path.Combine(DataDirectory, "history.json");
    public string IdentityPfxPath => IdentityPfxFile ?? Path.Combine(DataDirectory, "identity.pfx");
    public string IdentityKeyPath => IdentityKeyFile ?? Path.Combine(DataDirectory, "identity.key");
    public static HopDropPaths Default => new(
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "HopDrop"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HopDrop", "logs"));
    public string DefaultReceiveDirectory => ReceiveDirectory is null
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "HopDrop")
        : Path.GetFullPath(ReceiveDirectory);
}

/// <param name="AskBeforeReceiving">Files from devices that aren't trusted wait for the user's OK.</param>
/// <param name="Visibility">"everyone": discovery announces this device's name; "paired": announcements carry no name, so only paired devices recognise it.</param>
public sealed record AppSettings(string Name, string ReceiveDirectory, bool AskBeforeReceiving = false, string Visibility = "everyone");
/// <param name="Trusted">The user trusts this device: its files arrive without asking and without Windows' "downloaded file" mark.</param>
public sealed record PairedDevice(string Id, string Name, string Platform, DateTimeOffset PairedAt, List<string> LastAddresses, DateTimeOffset LastSeen, string? Alias = null, int Port = 7410, bool Trusted = false);
/// <param name="Count">How many files the transfer had when <paramref name="Files"/> keeps only the first ones; null when it lists them all.</param>
public sealed record HistoryEntry(DateTimeOffset At, string PeerId, string Direction, string Result, List<string> Files, string? Folder, int? Count = null)
{
    public int FileCount => Count ?? Files.Count;
}

/// <summary>Windows' "downloaded from another computer" mark (Mark of the Web), which makes Windows, SmartScreen and Office warn before opening risky files.</summary>
public static class ZoneMark
{
    private const string Content = "[ZoneTransfer]\r\nZoneId=3\r\n";
    /// <summary>False when the file's drive can't store the mark (FAT32/exFAT, some network shares) or this isn't Windows.</summary>
    public static bool Write(string path)
    {
        if (!OperatingSystem.IsWindows()) return false;
        try { File.WriteAllText(path + ":Zone.Identifier", Content); return true; }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException or ArgumentException) { return false; }
    }
    public static bool Has(string path)
    {
        try { return OperatingSystem.IsWindows() && File.ReadAllText(path + ":Zone.Identifier").Contains("ZoneId=3"); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException or ArgumentException) { return false; }
    }
    /// <summary>Whether a folder's drive can keep the mark (NTFS/ReFS do; FAT32 and exFAT don't). Null when unknown.</summary>
    public static bool? Supported(string folder)
    {
        try
        {
            string? root = Path.GetPathRoot(Path.GetFullPath(folder));
            if (string.IsNullOrEmpty(root) || root.StartsWith(@"\\")) return null;
            string format = new DriveInfo(root).DriveFormat;
            return format.Equals("NTFS", StringComparison.OrdinalIgnoreCase) || format.Equals("ReFS", StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException) { return null; }
    }
}

public sealed class JsonStore<T>(string path, T defaultValue)
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    public async Task<T> ReadAsync(CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct);
        try { return await ReadCoreAsync(ct); }
        finally { _gate.Release(); }
    }
    public async Task<T> UpdateAsync(Func<T, T> update, CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct);
        try
        {
            T value = update(await ReadCoreAsync(ct));
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                await using (var file = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.Asynchronous | FileOptions.WriteThrough))
                { await JsonSerializer.SerializeAsync(file, value, cancellationToken: ct); await file.FlushAsync(ct); }
                File.Move(temp, path, true);
            }
            finally { if (File.Exists(temp)) File.Delete(temp); }
            return value;
        }
        finally { _gate.Release(); }
    }
    private async Task<T> ReadCoreAsync(CancellationToken ct)
    {
        if (!File.Exists(path)) return defaultValue;
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.Asynchronous);
        return await JsonSerializer.DeserializeAsync<T>(stream, cancellationToken: ct) ?? defaultValue;
    }
}

public sealed class Identity : IDisposable
{
    public X509Certificate2 Certificate { get; }
    public string Id { get; }
    private Identity(X509Certificate2 certificate) { Certificate = certificate; Id = Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant(); }
    public static bool IsId(string? id) => id?.Length == 64 && id.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    public static string Fingerprint(X509Certificate2 cert) => Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant();
    public static Identity LoadOrCreate(HopDropPaths paths)
    {
        string pfx = paths.IdentityPfxPath, key = paths.IdentityKeyPath;
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(pfx))!);
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(key))!);
        if (File.Exists(pfx) && File.Exists(key))
        {
            string password = System.Text.Encoding.UTF8.GetString(Dpapi.Unprotect(File.ReadAllBytes(key)));
            return new(X509CertificateLoader.LoadPkcs12FromFile(pfx, password));
        }
        using var ec = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=HopDrop", ec, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        var uses = new OidCollection { new Oid("1.3.6.1.5.5.7.3.1"), new Oid("1.3.6.1.5.5.7.3.2") };
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(uses, false));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(30));
        string secret = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        File.WriteAllBytes(pfx, created.Export(X509ContentType.Pfx, secret));
        File.WriteAllBytes(key, Dpapi.Protect(System.Text.Encoding.UTF8.GetBytes(secret)));
        return new(X509CertificateLoader.LoadPkcs12FromFile(pfx, secret));
    }
    public void Dispose() => Certificate.Dispose();
}

internal static class Dpapi
{
    [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Length; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    public static byte[] Protect(byte[] value) => Transform(value, false);
    public static byte[] Unprotect(byte[] value) => Transform(value, true);
    private static byte[] Transform(byte[] value, bool unprotect)
    {
        IntPtr pointer = Marshal.AllocHGlobal(value.Length);
        try
        {
            Marshal.Copy(value, 0, pointer, value.Length);
            var input = new Blob { Length = value.Length, Data = pointer };
            Blob output;
            bool ok = unprotect ? CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, out output)
                : CryptProtectData(ref input, null, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 0, out output);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            try { byte[] result = new byte[output.Length]; Marshal.Copy(output.Data, result, 0, result.Length); return result; }
            finally { LocalFree(output.Data); }
        }
        finally { Marshal.FreeHGlobal(pointer); }
    }
}
