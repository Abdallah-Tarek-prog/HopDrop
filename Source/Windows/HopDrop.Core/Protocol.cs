using System.Buffers;
using System.Buffers.Binary;
using System.Text;
using System.Text.Json;

namespace HopDrop.Core;

public sealed class HopDropException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

public static class Framing
{
    public const int MaxData = 1_048_576;
    public const int MaxJson = 65_536;
    public static byte[] Encode(byte kind, ReadOnlySpan<byte> payload)
    {
        Validate(kind, payload.Length);
        byte[] frame = new byte[5 + payload.Length];
        frame[0] = kind;
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(1, 4), (uint)payload.Length);
        payload.CopyTo(frame.AsSpan(5));
        return frame;
    }
    public static async Task WriteAsync(Stream stream, byte kind, ReadOnlyMemory<byte> payload, CancellationToken ct = default)
    {
        Validate(kind, payload.Length);
        if (payload.Length <= MaxJson)
        {
            // One write, so header and payload travel in one TLS record instead of a 5-byte record of their own.
            await stream.WriteAsync(Encode(kind, payload.Span), ct);
        }
        else
        {
            byte[] header = new byte[5];
            header[0] = kind;
            BinaryPrimitives.WriteUInt32BigEndian(header.AsSpan(1), (uint)payload.Length);
            await stream.WriteAsync(header, ct);
            await stream.WriteAsync(payload, ct);
        }
        await stream.FlushAsync(ct);
    }
    /// <summary>Sends a data frame whose <paramref name="count"/> payload bytes already sit at offset 5 of <paramref name="frame"/>, in one write.</summary>
    public static async Task WriteDataAsync(Stream stream, byte[] frame, int count, CancellationToken ct = default)
    {
        Validate(2, count);
        frame[0] = 2;
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(1, 4), (uint)count);
        await stream.WriteAsync(frame.AsMemory(0, 5 + count), ct);
        await stream.FlushAsync(ct);
    }
    public static async Task<(byte Kind, byte[] Payload)> ReadAsync(Stream stream, CancellationToken ct = default)
    {
        byte[] header = new byte[5];
        await stream.ReadExactlyAsync(header, ct);
        uint size = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(1));
        if (size > MaxData) throw new HopDropException("protocol_error", "Invalid frame length");
        Validate(header[0], (int)size);
        byte[] payload = new byte[size];
        await stream.ReadExactlyAsync(payload, ct);
        return (header[0], payload);
    }
    internal static void Validate(byte kind, int size)
    {
        if ((kind != 1 && kind != 2) || size < 1 || size > (kind == 1 ? MaxJson : MaxData))
            throw new HopDropException("protocol_error", "Invalid frame kind or length");
    }
}

/// <summary>Reads frames into one reusable buffer, so receiving a file doesn't allocate a new 1 MB array per data frame.</summary>
public sealed class FrameReader(Stream stream) : IDisposable
{
    private readonly byte[] _header = new byte[5];
    private byte[]? _buffer = ArrayPool<byte>.Shared.Rent(Framing.MaxData);
    /// <summary>The payload is only valid until the next read.</summary>
    public async Task<(byte Kind, ReadOnlyMemory<byte> Payload)> ReadAsync(CancellationToken ct = default)
    {
        byte[] buffer = _buffer ?? throw new ObjectDisposedException(nameof(FrameReader));
        await stream.ReadExactlyAsync(_header, ct);
        uint size = BinaryPrimitives.ReadUInt32BigEndian(_header.AsSpan(1));
        if (size > Framing.MaxData) throw new HopDropException("protocol_error", "Invalid frame length");
        Framing.Validate(_header[0], (int)size);
        await stream.ReadExactlyAsync(buffer.AsMemory(0, (int)size), ct);
        return (_header[0], buffer.AsMemory(0, (int)size));
    }
    public void Dispose()
    {
        byte[]? buffer = Interlocked.Exchange(ref _buffer, null);
        if (buffer is not null) ArrayPool<byte>.Shared.Return(buffer);
    }
}

public static class Messages
{
    public static async Task WriteAsync(Stream stream, object value, CancellationToken ct = default) =>
        await Framing.WriteAsync(stream, 1, JsonSerializer.SerializeToUtf8Bytes(value), ct);

    public static async Task<JsonDocument> ReadAsync(Stream stream, CancellationToken ct = default)
    {
        var (kind, payload) = await Framing.ReadAsync(stream, ct);
        if (kind != 1) throw new HopDropException("protocol_error", "Expected control frame");
        try
        {
            var doc = JsonDocument.Parse(payload);
            if (doc.RootElement.ValueKind != JsonValueKind.Object || !doc.RootElement.TryGetProperty("type", out var type) || type.ValueKind != JsonValueKind.String)
                throw new HopDropException("protocol_error", "Invalid control message");
            return doc;
        }
        catch (JsonException e) { throw new HopDropException("protocol_error", e.Message); }
    }
    public static string String(JsonElement obj, string property) => obj.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String
        ? value.GetString()! : throw new HopDropException("protocol_error", $"Missing {property}");
    public static long Long(JsonElement obj, string property) => obj.TryGetProperty(property, out var value) && value.TryGetInt64(out long n)
        ? n : throw new HopDropException("protocol_error", $"Missing {property}");
    public static bool Bool(JsonElement obj, string property) => obj.TryGetProperty(property, out var value) && (value.ValueKind is JsonValueKind.True or JsonValueKind.False)
        ? value.GetBoolean() : throw new HopDropException("protocol_error", $"Missing {property}");
    public static string Type(JsonElement obj) => String(obj, "type");
    public static void ThrowIfError(JsonElement obj)
    {
        if (Type(obj) == "error") throw new HopDropException(String(obj, "code"), String(obj, "message"));
    }
}
