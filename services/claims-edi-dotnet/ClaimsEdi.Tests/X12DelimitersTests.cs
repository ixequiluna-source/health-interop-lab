using System;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class X12DelimitersTests
{
    [Fact]
    public void DefaultSetIsTheConventional005010Delimiters()
    {
        X12Delimiters delimiters = X12Delimiters.Default;

        Assert.Equal('*', delimiters.Element);
        Assert.Equal(':', delimiters.Component);
        Assert.Equal('^', delimiters.Repetition);
        Assert.Equal('~', delimiters.Segment);
    }

    [Fact]
    public void TwoDelimitersMayNotBeTheSameCharacter()
    {
        ArgumentException error = Assert.Throws<ArgumentException>(
            () => new X12Delimiters('*', '*', '^', '~'));

        Assert.Contains("ambiguous", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData('A')]
    [InlineData('z')]
    [InlineData('7')]
    public void ADelimiterMayNotBeALetterOrDigit(char candidate)
    {
        // A letter or digit as a separator would make 'ISA' itself unparseable, and would split
        // every alphanumeric code element in the file.
        ArgumentException error = Assert.Throws<ArgumentException>(
            () => new X12Delimiters(candidate, ':', '^', '~'));

        Assert.Contains("letter or digit", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void TheSegmentTerminatorMayBeANewline()
    {
        // Mainframe senders routinely terminate segments with a line feed. This must be accepted;
        // it is the one delimiter allowed to be a control character.
        var delimiters = new X12Delimiters('*', ':', '^', '\n');

        Assert.Equal('\n', delimiters.Segment);
        Assert.Equal("\\n", X12Delimiters.Describe(delimiters.Segment));
    }

    [Fact]
    public void TheElementSeparatorMayNotBeWhitespace()
    {
        Assert.Throws<ArgumentException>(() => new X12Delimiters(' ', ':', '^', '~'));
    }

    [Theory]
    [InlineData("O*BRIEN", 1)]
    [InlineData("A:B", 1)]
    [InlineData("SEG~MENT", 3)]
    [InlineData("REP^EAT", 3)]
    public void ForbiddenCharactersAreFoundAtTheirOffset(string value, int expectedOffset)
    {
        Assert.Equal(expectedOffset, X12Delimiters.Default.IndexOfForbidden(value));
    }

    [Theory]
    [InlineData("line\rbreak", 4)]
    [InlineData("line\nbreak", 4)]
    public void CarriageReturnAndLineFeedAreForbiddenInData(string value, int expectedOffset)
    {
        // Readers trim line endings around segment terminators so that pretty-printed EDI parses.
        // A CR or LF inside a value is therefore indistinguishable from formatting.
        Assert.Equal(expectedOffset, X12Delimiters.Default.IndexOfForbidden(value));
    }

    [Fact]
    public void CleanValuesReportNoForbiddenCharacter()
    {
        Assert.Equal(-1, X12Delimiters.Default.IndexOfForbidden("LUNA"));
    }

    [Fact]
    public void RemoveForbiddenStripsEveryDelimiter()
    {
        Assert.Equal("OBRIEN", X12Delimiters.Default.RemoveForbidden("O*BRIEN"));
        Assert.Equal("AB", X12Delimiters.Default.RemoveForbidden("A~B"));
    }

    [Fact]
    public void ForbiddennessFollowsTheActualDelimiterSet()
    {
        var pipes = new X12Delimiters('|', '>', '+', '\n');

        // A star is data in this interchange, and a pipe is not.
        Assert.Equal(-1, pipes.IndexOfForbidden("O*BRIEN"));
        Assert.Equal(1, pipes.IndexOfForbidden("O|BRIEN"));
    }

    [Fact]
    public void EqualityIsByValue()
    {
        Assert.Equal(new X12Delimiters('*', ':', '^', '~'), X12Delimiters.Default);
        Assert.NotEqual(new X12Delimiters('|', ':', '^', '~'), X12Delimiters.Default);
    }
}
