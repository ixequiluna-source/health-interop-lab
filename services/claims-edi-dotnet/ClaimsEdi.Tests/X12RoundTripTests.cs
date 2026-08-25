using System;
using System.Collections.Generic;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

/// <summary>
/// The round-trip property: writing an interchange and reading it back yields equivalent data, and
/// writing that back again is byte-identical.
/// </summary>
/// <remarks>
/// The second half is what makes the property useful. Structural equality alone tolerates a writer
/// that normalises something on every pass; requiring the re-serialised text to be identical means
/// the encoding has a fixed point, which is what a partner diffing two submissions actually sees.
/// </remarks>
public sealed class X12RoundTripTests
{
    public static TheoryData<char, char, char, char> DelimiterSets() => new()
    {
        { '*', ':', '^', '~' },   // the conventional set
        { '|', '>', '+', '\n' },  // pipes and a newline terminator, as mainframe senders send
        { '#', '@', '%', '$' },   // nothing in common with the defaults
    };

    [Theory]
    [MemberData(nameof(DelimiterSets))]
    public void AnInterchangeSurvivesAWriteReadWriteCycle(
        char element,
        char component,
        char repetition,
        char segment)
    {
        var delimiters = new X12Delimiters(element, component, repetition, segment);
        X12Interchange original = TestData.BuildClaimInterchange(delimiters: delimiters);

        var writer = new X12Writer(delimiters);
        string first = writer.Write(original);

        X12Interchange parsed = new X12Reader().Read(first);
        string second = writer.Write(parsed);

        Assert.Equal(first, second);
        Assert.Equal(delimiters, parsed.Delimiters);
        AssertEquivalent(original, parsed);
    }

    [Fact]
    public void CompositesSurviveTheRoundTripAsComposites()
    {
        X12Interchange original = TestData.BuildClaimInterchange();
        string edi = new X12Writer().Write(original);

        X12TransactionSet parsed = TestData.OnlySet(new X12Reader().Read(edi));

        X12Segment claim = TestData.Find(parsed, "CLM");
        Assert.True(claim.Element(5).IsComposite);
        Assert.Equal(3, claim.Element(5).ComponentCount);
        Assert.Equal("21", claim.Component(5, 1));
        Assert.Equal("B", claim.Component(5, 2));
        Assert.Equal("1", claim.Component(5, 3));

        X12Segment procedure = TestData.Find(parsed, "SV1");
        Assert.Equal("HC", procedure.Component(1, 1));
        Assert.Equal("99223", procedure.Component(1, 2));
        Assert.Equal("25", procedure.Component(1, 3));
    }

    [Fact]
    public void ADelimiterInDataIsRefusedRatherThanSilentlyCorruptingTheRoundTrip()
    {
        // The interchange this would produce is syntactically valid and semantically wrong: the
        // payer reads NM104 out of what was meant to be the second half of the surname. There is no
        // escape sequence in X12, so the only honest options are to refuse or to strip.
        AdmissionEvent admission = TestData.Admission(familyName: "O*BRIEN");
        X12Interchange interchange = TestData.BuildClaimInterchange(TestData.Claim(admission));

        X12EncodingException error = Assert.Throws<X12EncodingException>(
            () => new X12Writer().Write(interchange));

        Assert.Equal(X12ErrorCode.DelimiterInData, error.Code);
    }

    [Fact]
    public void TheStripPolicyStillRoundTrips()
    {
        AdmissionEvent admission = TestData.Admission(familyName: "O*BRIEN");
        X12Interchange interchange = TestData.BuildClaimInterchange(TestData.Claim(admission));

        var writer = new X12Writer(X12Delimiters.Default, X12DelimiterPolicy.Strip);
        string first = writer.Write(interchange);

        X12Interchange parsed = new X12Reader().Read(first);
        Assert.Equal(first, writer.Write(parsed));

        X12Segment subscriber = TestData.Find(TestData.OnlySet(parsed), "NM1", "IL");
        Assert.Equal("OBRIEN", subscriber.Value(3));
    }

    [Fact]
    public void ADelimiterInDataIsHarmlessWhenItIsNotThisInterchangesDelimiter()
    {
        // The same surname is perfectly representable for a partner that uses pipes. This is the
        // whole reason the forbidden set is derived from the interchange rather than hard-coded.
        AdmissionEvent admission = TestData.Admission(familyName: "O*BRIEN");
        var delimiters = new X12Delimiters('|', '>', '+', '\n');

        X12Interchange interchange = TestData.BuildClaimInterchange(
            TestData.Claim(admission), delimiters);

        string edi = new X12Writer(delimiters).Write(interchange);
        X12Interchange parsed = new X12Reader().Read(edi);

        Assert.Equal("O*BRIEN", TestData.Find(TestData.OnlySet(parsed), "NM1", "IL").Value(3));
    }

    private static void AssertEquivalent(X12Interchange expected, X12Interchange actual)
    {
        Assert.Equal(expected.Header, actual.Header);
        Assert.Equal(expected.Groups.Count, actual.Groups.Count);

        for (int g = 0; g < expected.Groups.Count; g++)
        {
            X12FunctionalGroup expectedGroup = expected.Groups[g];
            X12FunctionalGroup actualGroup = actual.Groups[g];

            Assert.Equal(expectedGroup.Header, actualGroup.Header);
            Assert.Equal(expectedGroup.TransactionSets.Count, actualGroup.TransactionSets.Count);

            for (int t = 0; t < expectedGroup.TransactionSets.Count; t++)
            {
                X12TransactionSet expectedSet = expectedGroup.TransactionSets[t];
                X12TransactionSet actualSet = actualGroup.TransactionSets[t];

                Assert.Equal(expectedSet.IdentifierCode, actualSet.IdentifierCode);
                Assert.Equal(expectedSet.ControlNumber, actualSet.ControlNumber);
                Assert.Equal(expectedSet.ImplementationReference, actualSet.ImplementationReference);

                IReadOnlyList<X12Segment> expectedSegments = expectedSet.Segments;
                IReadOnlyList<X12Segment> actualSegments = actualSet.Segments;

                Assert.Equal(expectedSegments.Count, actualSegments.Count);
                for (int s = 0; s < expectedSegments.Count; s++)
                {
                    Assert.Equal(expectedSegments[s], actualSegments[s]);
                }
            }
        }
    }
}
