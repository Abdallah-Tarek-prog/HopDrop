using System.Net.Sockets;
using HopDrop.Linux;
using Xunit;
using LinuxStrings = HopDrop.Linux.L;

namespace HopDrop.Tests;

public class LinuxLocalizationTests
{
    [Fact]
    public void EnglishDefaultAndArabicResourcesUseTheCorrectPluralForms()
    {
        string previous = LinuxStrings.Language;
        try
        {
            Assert.Equal("en", new LinuxPreferences().Language);
            LinuxStrings.Language = "en";
            Assert.Equal("1 file", LinuxStrings.Files(1));
            Assert.Equal("2 files", LinuxStrings.Files(2));
            LinuxStrings.Language = "ar";
            Assert.Equal("لا توجد ملفات", LinuxStrings.Files(0));
            Assert.Equal("ملف واحد", LinuxStrings.Files(1));
            Assert.Equal("ملفان", LinuxStrings.Files(2));
            Assert.EndsWith(" ملفات", LinuxStrings.Files(3));
            Assert.EndsWith(" ملفًا", LinuxStrings.Files(11));
            Assert.EndsWith(" ملف", LinuxStrings.Files(100));
            Assert.Contains("تعذّر", LinuxStrings.Error(new SocketException()));
            string message = LinuxStrings.Get("offer_message", "Laptop 6 Pro", LinuxStrings.Files(2));
            Assert.Contains("\u2068Laptop 6 Pro\u2069", message);
            Assert.DoesNotContain("wants to send", message);
        }
        finally { LinuxStrings.Language = previous; }
    }
}
