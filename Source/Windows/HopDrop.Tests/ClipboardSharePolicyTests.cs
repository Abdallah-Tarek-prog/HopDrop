using HopDrop.Core;
using Xunit;

namespace HopDrop.Tests;

public class ClipboardSharePolicyTests
{
    [Theory]
    [InlineData(true, false, null, null)]
    [InlineData(false, true, null, null)]
    [InlineData(false, false, 0, null)]
    [InlineData(false, false, null, 0)]
    public void PrivateFormatsAreSkipped(bool exclude, bool ignore, int? history, int? cloud)
    {
        Assert.True(ClipboardSharePolicy.IsPrivate(exclude, ignore, history, cloud));
        Assert.False(ClipboardSharePolicy.ShouldShare(true, "password", null, null, true));
    }

    [Fact]
    public void OrdinaryClipboardDecision()
    {
        Assert.False(ClipboardSharePolicy.IsPrivate(false, false, 1, 1));
        Assert.False(ClipboardSharePolicy.ShouldShare(false, "text", null, null, false));
        Assert.False(ClipboardSharePolicy.ShouldShare(true, new string('a', HopDropPeer.MaxText + 1), null, null, false));
        Assert.False(ClipboardSharePolicy.ShouldShare(true, "text", "text", null, false));
        Assert.False(ClipboardSharePolicy.ShouldShare(true, "text", null, "text", false));
        Assert.True(ClipboardSharePolicy.ShouldShare(true, "text", null, null, false));
    }
}
