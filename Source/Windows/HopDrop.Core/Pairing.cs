using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace HopDrop.Core;

public static class Sas
{
    public static string Commit(ReadOnlySpan<byte> fpR, ReadOnlySpan<byte> fpI, ReadOnlySpan<byte> nR) =>
        Convert.ToHexString(SHA256.HashData(Combine("HopDrop-SAS-commit", fpR, fpI, nR))).ToLowerInvariant();
    public static bool CommitMatches(string fromWire, ReadOnlySpan<byte> fpR, ReadOnlySpan<byte> fpI, ReadOnlySpan<byte> nR) =>
        string.Equals(fromWire, Commit(fpR, fpI, nR), StringComparison.OrdinalIgnoreCase);
    public static string Code(ReadOnlySpan<byte> fpI, ReadOnlySpan<byte> fpR, ReadOnlySpan<byte> nI, ReadOnlySpan<byte> nR)
    {
        byte[] hash = SHA256.HashData(Combine("HopDrop-SAS-code", fpI, fpR, nI, nR));
        uint n = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(hash);
        return (n % 1_000_000).ToString("D6");
    }
    private static byte[] Combine(string label, ReadOnlySpan<byte> a, ReadOnlySpan<byte> b, ReadOnlySpan<byte> c)
    {
        byte[] bytes = new byte[Encoding.ASCII.GetByteCount(label) + a.Length + b.Length + c.Length];
        int offset = Encoding.ASCII.GetBytes(label, bytes);
        a.CopyTo(bytes.AsSpan(offset)); offset += a.Length;
        b.CopyTo(bytes.AsSpan(offset)); offset += b.Length;
        c.CopyTo(bytes.AsSpan(offset));
        return bytes;
    }
    private static byte[] Combine(string label, ReadOnlySpan<byte> a, ReadOnlySpan<byte> b, ReadOnlySpan<byte> c, ReadOnlySpan<byte> d)
    {
        byte[] first = Combine(label, a, b, c);
        byte[] bytes = new byte[first.Length + d.Length];
        first.CopyTo(bytes, 0);
        d.CopyTo(bytes.AsSpan(first.Length));
        return bytes;
    }
}

public sealed record PairUri(string Id, string Name, string Platform, int Port, IReadOnlyList<IPAddress> Addresses, byte[] Token)
{
    public override string ToString() => $"hopdrop://pair?v=2&id={Id}&n={Uri.EscapeDataString(Name)}&pl={Platform}&p={Port}&a={string.Join(',', Addresses)}&t={TokenCodec.Encode(Token)}";
    public static PairUri Parse(string uri)
    {
        if (!Uri.TryCreate(uri, UriKind.Absolute, out var value) || value.Scheme != "hopdrop" || value.Host != "pair") throw new FormatException("Invalid pair URI");
        var parts = value.Query.TrimStart('?').Split('&').Select(s => s.Split('=', 2)).ToDictionary(s => s[0], s => s.Length == 2 ? s[1] : "", StringComparer.Ordinal);
        if (!parts.TryGetValue("v", out string? version) || version != "2" || !parts.TryGetValue("id", out string? id) || !Identity.IsId(id)
            || !parts.TryGetValue("n", out string? rawName) || !parts.TryGetValue("pl", out string? pl) || pl is not ("android" or "windows")
            || !parts.TryGetValue("p", out string? rawPort) || !int.TryParse(rawPort, out int port) || port < 1 || port > 65535
            || !parts.TryGetValue("a", out string? rawAddresses) || !parts.TryGetValue("t", out string? rawToken)) throw new FormatException("Invalid pair URI");
        string name = Uri.UnescapeDataString(rawName);
        if (name.Length is < 1 or > 40) throw new FormatException("Invalid name");
        IPAddress[] addresses = rawAddresses.Split(',', StringSplitOptions.RemoveEmptyEntries).Select(IPAddress.Parse).ToArray();
        if (addresses.Length == 0 || addresses.Any(a => a.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)) throw new FormatException("Invalid addresses");
        byte[] token = TokenCodec.Decode(rawToken);
        return new(id, name, pl, port, addresses, token);
    }
}

public static class TokenCodec
{
    public static string Encode(ReadOnlySpan<byte> value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    public static byte[] Decode(string text)
    {
        if (text.Length != 22 || text.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))) throw new FormatException("Invalid token");
        byte[] bytes = Convert.FromBase64String(text.Replace('-', '+').Replace('_', '/') + "==");
        if (bytes.Length != 16) throw new FormatException("Invalid token");
        return bytes;
    }
}

public sealed class OneTimeToken(TimeProvider? clock = null)
{
    private readonly TimeProvider _clock = clock ?? TimeProvider.System;
    private readonly object _gate = new();
    private byte[]? _token;
    private DateTimeOffset _expires;
    public string Issue()
    {
        lock (_gate)
        {
            _token = RandomNumberGenerator.GetBytes(16);
            _expires = _clock.GetUtcNow().AddMinutes(5);
            return TokenCodec.Encode(_token);
        }
    }
    public void Invalidate() { lock (_gate) _token = null; }
    public string Consume(string candidate)
    {
        lock (_gate)
        {
            if (_token is null) return "bad_token";
            if (_clock.GetUtcNow() >= _expires) { _token = null; return "token_expired"; }
            byte[] other;
            try { other = TokenCodec.Decode(candidate); } catch (FormatException) { return "bad_token"; }
            if (!CryptographicOperations.FixedTimeEquals(_token, other)) return "bad_token";
            _token = null;
            return "pair_ok";
        }
    }
}
