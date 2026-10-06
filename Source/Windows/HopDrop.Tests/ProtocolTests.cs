using System.Net;
using System.Security.Cryptography;
using System.Text;
using HopDrop.Core;
using Xunit;

namespace HopDrop.Tests;

public class ProtocolTests
{
    [Fact]
    public async Task FramingExampleAndRoundTrip()
    {
        byte[] example = Convert.FromHexString("010000000F7B2274797065223A2270696E67227D");
        Assert.Equal(example, Framing.Encode(1, Encoding.UTF8.GetBytes("{\"type\":\"ping\"}")));
        await using var stream = new MemoryStream(example);
        var frame = await Framing.ReadAsync(stream);
        Assert.Equal((byte)1, frame.Kind);
        Assert.Equal("{\"type\":\"ping\"}", Encoding.UTF8.GetString(frame.Payload));
        byte[] data = RandomNumberGenerator.GetBytes(Framing.MaxData);
        await using var large = new MemoryStream(Framing.Encode(2, data));
        Assert.Equal(data, (await Framing.ReadAsync(large)).Payload);
    }
    [Theory]
    [InlineData("03000000017F")]
    [InlineData("0100000000")]
    [InlineData("0200000000")]
    [InlineData("0100010001")]
    [InlineData("0200100001")]
    public async Task InvalidFrames(string hex)
    {
        await using var stream = new MemoryStream(Convert.FromHexString(hex));
        var e = await Assert.ThrowsAsync<HopDropException>(() => Framing.ReadAsync(stream));
        Assert.Equal("protocol_error", e.Code);
    }
    [Fact]
    public void InvalidFrameEncoding()
    {
        Assert.Equal("protocol_error", Assert.Throws<HopDropException>(() => Framing.Encode(3, [1])).Code);
        Assert.Equal("protocol_error", Assert.Throws<HopDropException>(() => Framing.Encode(1, [])).Code);
        Assert.Equal("protocol_error", Assert.Throws<HopDropException>(() => Framing.Encode(2, new byte[Framing.MaxData + 1])).Code);
    }
    [Theory]
    [InlineData("report.pdf", "report.pdf")]
    [InlineData("../evil.txt", "evil.txt")]
    [InlineData("C:\\Windows\\x.dll", "x.dll")]
    [InlineData("con.txt", "_con.txt")]
    [InlineData("COM1", "_COM1")]
    [InlineData("what?.txt", "what_.txt")]
    [InlineData("name. ", "name")]
    [InlineData("", "file")]
    [InlineData("..", "file")]
    [InlineData("تقرير 📁.pdf", "تقرير 📁.pdf")]
    [InlineData("\u0001a.txt", "_a.txt")]
    public void FileNameTable(string input, string expected) => Assert.Equal(expected, FileNames.Sanitize(input));

    [Theory]
    [InlineData("Meeting at 5 pm.\nBring the slides", "Meeting at 5 pm.txt")]
    [InlineData("  \r\n https://example.com/a", "Link.txt")]
    [InlineData("   ", "Text.txt")]
    [InlineData("???", "Text.txt")]
    [InlineData("The quick brown fox jumps over the lazy dog again and again", "The quick brown fox jumps over the lazy.txt")]
    [InlineData("a/b: c", "a_b_ c.txt")]
    public void TextFileNames(string text, string expected) => Assert.Equal(expected, FileNames.ForText(text));
    [Fact]
    public void LongNamesAndUniqueNames()
    {
        Assert.Equal(new string('a', 176) + ".txt", FileNames.Sanitize(new string('a', 250) + ".txt"));
        Assert.Equal("report (2).pdf", FileNames.Unique("report.pdf", n => n is "report.pdf" or "report (1).pdf"));
        Assert.Equal("README (1)", FileNames.Unique("README", n => n == "README"));
        Assert.Equal(".hidden (1)", FileNames.Unique(".hidden", n => n == ".hidden"));
        Assert.True(FileNames.Sanitize(new string('a', 178) + "😀.txt").Length <= 180);
        Assert.True(FileNames.Sanitize("a." + new string('x', 250)).Length <= 180);
    }
    [Theory]
    [InlineData("1111111111111111111111111111111111111111111111111111111111111111", "2222222222222222222222222222222222222222222222222222222222222222", "3333333333333333333333333333333333333333333333333333333333333333", "4444444444444444444444444444444444444444444444444444444444444444", "6935596fda9b27748a1b439b2172499fbf95b23fc48787576c9def67405cf3b6", "219471")]
    [InlineData("59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419", "6dcde155f1f900e157d25bb5c24b1a54d71ea35b78661ed9b1a8be3532c3d4f5", "90c25e389b9cdf641350c7572c1d030f051747d44809a6ec1243eb9227fc0c96", "433d46ffcc5cd2238a19974a19da7eb21770b0d908ed20ebb2b91ceb230b6d40", "5e8269ab296d63bb36e4f46c832c56140eb138618809dc51c4de68652dc46bd5", "447251")]
    public void SasVectors(string fi, string fr, string ni, string nr, string commit, string code)
    {
        byte[] a = Convert.FromHexString(fi), b = Convert.FromHexString(fr), c = Convert.FromHexString(ni), d = Convert.FromHexString(nr);
        Assert.Equal(commit, Sas.Commit(b, a, d));
        Assert.True(Sas.CommitMatches(commit.ToUpperInvariant(), b, a, d));
        Assert.False(Sas.CommitMatches(new string('0', 64), b, a, d));
        Assert.Equal(code, Sas.Code(a, b, c, d));
    }
    [Fact]
    public void QrExampleAndBadInputs()
    {
        const string example = "hopdrop://pair?v=2&id=59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419&n=LOQ%20Laptop&pl=windows&p=7410&a=192.168.1.5,192.168.137.1&t=AAECAwQFBgcICQoLDA0ODw";
        var uri = PairUri.Parse(example);
        Assert.Equal("LOQ Laptop", uri.Name);
        Assert.Equal(new[] { IPAddress.Parse("192.168.1.5"), IPAddress.Parse("192.168.137.1") }, uri.Addresses);
        Assert.Equal(Enumerable.Range(0, 16).Select(n => (byte)n), uri.Token);
        Assert.Equal(example, uri.ToString());
        Assert.Equal("أحمد 📁", PairUri.Parse((uri with { Name = "أحمد 📁" }).ToString()).Name);
        Assert.Throws<FormatException>(() => PairUri.Parse(example.Replace("v=2", "v=3")));
        Assert.Throws<FormatException>(() => PairUri.Parse(example.Replace("p=7410", "p=0")));
        Assert.Throws<FormatException>(() => PairUri.Parse(example.Replace("&t=AAECAwQFBgcICQoLDA0ODw", "&t=bad")));
        Assert.Throws<FormatException>(() => PairUri.Parse("https://pair"));
    }
    [Fact]
    public void TokenExpiresAndIsSingleUse()
    {
        var clock = new FakeClock(); var token = new OneTimeToken(clock);
        string value = token.Issue();
        Assert.Equal("bad_token", token.Consume(TokenCodec.Encode(RandomNumberGenerator.GetBytes(16))));
        Assert.Equal("pair_ok", token.Consume(value));
        Assert.Equal("bad_token", token.Consume(value));
        value = token.Issue(); clock.Advance(TimeSpan.FromMinutes(5));
        Assert.Equal("token_expired", token.Consume(value));
    }
    [Fact]
    public void DiscoveryParseAndExpiry()
    {
        string id = new('a', 64);
        var message = new DiscoveryMessage(2, "announce", id, "Laptop", "windows", 7410, true);
        Assert.Equal(message, DiscoveryMessage.Parse(message.Encode()));
        Assert.Throws<HopDropException>(() => DiscoveryMessage.Parse(new byte[1201]));
        Assert.Throws<HopDropException>(() => DiscoveryMessage.Parse(Encoding.UTF8.GetBytes("{\"type\":\"other\"}")));
        var clock = new FakeClock(); var table = new NearbyTable(clock);
        Assert.False(table.Update(message, IPAddress.Loopback, id));
        Assert.True(table.Update(message, IPAddress.Loopback, new string('b', 64)));
        Assert.False(table.Update(message, IPAddress.Loopback, new string('b', 64)));
        Assert.True(table.Update(message with { Name = "Renamed" }, IPAddress.Loopback, new string('b', 64)));
        Assert.True(table.Update(message with { Receive = false }, IPAddress.Loopback, new string('b', 64)));
        clock.Advance(TimeSpan.FromSeconds(44));
        Assert.False(table.Expire());
        clock.Advance(TimeSpan.FromSeconds(1));
        Assert.True(table.Expire()); Assert.Empty(table.Items);
    }
    [Fact]
    public async Task JsonStoreSerializesConcurrentAtomicUpdates()
    {
        string root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.test-scratch", Guid.NewGuid().ToString("N")));
        Directory.CreateDirectory(root);
        var store = new JsonStore<List<int>>(Path.Combine(root, "settings.json"), []);
        await Task.WhenAll(Enumerable.Range(0, 50).Select(i => store.UpdateAsync(old => { old.Add(i); return old; })));
        Assert.Equal(Enumerable.Range(0, 50), (await store.ReadAsync()).Order());
        Assert.Empty(Directory.GetFiles(root, "*.tmp"));
    }
    [Fact]
    public async Task FrameReaderAndSingleWriteDataFramesRoundTrip()
    {
        using var stream = new MemoryStream();
        await Framing.WriteAsync(stream, 1, Encoding.UTF8.GetBytes("{\"type\":\"file\",\"i\":0}"));
        byte[] frame = new byte[5 + 3]; "abc"u8.CopyTo(frame.AsSpan(5));
        await Framing.WriteDataAsync(stream, frame, 3);
        Assert.Equal(Framing.Encode(2, "abc"u8), stream.ToArray()[^8..]);
        stream.Position = 0;
        using var reader = new FrameReader(stream);
        var first = await reader.ReadAsync();
        Assert.Equal(1, first.Kind);
        var second = await reader.ReadAsync();
        Assert.Equal(2, second.Kind);
        Assert.Equal("abc"u8.ToArray(), second.Payload.ToArray());
    }
    [Fact]
    public void HiddenDiscoveryAnnouncementsHaveNoName()
    {
        var hidden = new DiscoveryMessage(2, "announce", new string('a', 64), "", "android", 7410, true);
        Assert.Equal("", DiscoveryMessage.Parse(hidden.Encode()).Name);
    }
    [Fact]
    public void SendItemsKeepFolderStructureAndSkipHiddenFiles()
    {
        string root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.test-scratch", Guid.NewGuid().ToString("N")));
        string photos = Path.Combine(root, "Photos"); Directory.CreateDirectory(Path.Combine(photos, "2024", "July"));
        File.WriteAllText(Path.Combine(photos, "cover.jpg"), "c"); File.WriteAllText(Path.Combine(photos, "2024", "July", "beach.jpg"), "b");
        string hidden = Path.Combine(photos, "Thumbs.db"); File.WriteAllText(hidden, "h"); File.SetAttributes(hidden, FileAttributes.Hidden);
        string loose = Path.Combine(root, "loose.txt"); File.WriteAllText(loose, "l");
        var items = SendItem.From([photos, loose, loose]);
        Assert.Equal(3, items.Count);
        Assert.Contains(new SendItem(Path.Combine(photos, "cover.jpg"), "Photos", photos), items);
        Assert.Contains(new SendItem(Path.Combine(photos, "2024", "July", "beach.jpg"), "Photos/2024/July", photos), items);
        Assert.Contains(new SendItem(loose), items);
    }
    private sealed class FakeClock : TimeProvider
    {
        private DateTimeOffset _now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan delta) => _now += delta;
    }
}
