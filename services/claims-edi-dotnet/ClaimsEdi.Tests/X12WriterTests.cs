using System;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class X12WriterTests
{
    [Fact]
    public void IsaElementWidthsSumToTheFixedSegmentLength()
    {
        // 3 for "ISA", 1 separator after the identifier, then every element followed by either a
        // separator or the segment terminator.
        int total = 3 + 1 + X12IsaLayout.ElementCount;
        for (int element = 1; element <= X12IsaLayout.ElementCount; element++)
        {
            total += X12IsaLayout.ElementWidth(element);
        }

        Assert.Equal(X12IsaLayout.SegmentLength, total);
    }

    [Fact]
    public void TheIsaSegmentIsExactly106Characters()
    {
        string edi = TestData.MinimalEdi();

        int terminator = edi.IndexOf('~');

        Assert.Equal(X12IsaLayout.SegmentTerminatorIndex, terminator);
        Assert.Equal(X12IsaLayout.SegmentLength, terminator + 1);
    }

    [Fact]
    public void AlphanumericIsaElementsAreSpacePaddedToTheirFixedWidth()
    {
        string edi = TestData.MinimalEdi();

        // ISA06 occupies offsets 35..49 and is 15 characters wide. "FIRMUSHEALTH" is twelve, so
        // three trailing spaces are part of the encoding — not of the sender id.
        Assert.Equal("FIRMUSHEALTH   ", edi.Substring(35, 15));

        // ISA02 is blank, which means ten spaces rather than nothing at all.
        Assert.Equal("          ", edi.Substring(7, 10));

        // ISA08, 15 wide.
        Assert.Equal("CLEARINGHOUSE  ", edi.Substring(54, 15));
    }

    [Fact]
    public void TheInterchangeControlNumberIsZeroFilledToNineDigits()
    {
        var interchange = TestData.Envelope(new X12Segment("BHT", "0019")) with
        {
            Header = TestData.Header("42"),
        };

        string edi = new X12Writer().Write(interchange);

        Assert.Equal("000000042", edi.Substring(90, 9));
    }

    [Fact]
    public void Isa11AndIsa16CarryTheDelimitersThemselves()
    {
        var delimiters = new X12Delimiters('|', '>', '+', '\n');
        string edi = TestData.MinimalEdi(delimiters);

        Assert.Equal('+', edi[X12IsaLayout.RepetitionSeparatorIndex]);
        Assert.Equal('>', edi[X12IsaLayout.ComponentSeparatorIndex]);
        Assert.Equal('\n', edi[X12IsaLayout.SegmentTerminatorIndex]);
        Assert.Equal('|', edi[X12IsaLayout.ElementSeparatorIndex]);
    }

    [Fact]
    public void ANonDefaultDelimiterSetStillProducesA106CharacterIsa()
    {
        var delimiters = new X12Delimiters('|', '>', '+', '\n');
        string edi = TestData.MinimalEdi(delimiters);

        Assert.Equal(X12IsaLayout.SegmentTerminatorIndex, edi.IndexOf('\n'));
        Assert.DoesNotContain("*", edi.Substring(0, X12IsaLayout.SegmentLength), StringComparison.Ordinal);
    }

    [Fact]
    public void Se01CountsTheTransactionSetIncludingStAndSe()
    {
        // Three body segments: ST + 3 + SE = 5.
        string edi = TestData.MinimalEdi();
        string se = TestData.RawSegment(edi, "SE");

        Assert.Equal("SE*5*0001", se);
    }

    [Fact]
    public void Se02EchoesSt02()
    {
        string edi = TestData.MinimalEdi();

        Assert.StartsWith("ST*837*0001*005010X222A1", TestData.RawSegment(edi, "ST"), StringComparison.Ordinal);
        Assert.EndsWith("*0001", TestData.RawSegment(edi, "SE"), StringComparison.Ordinal);
    }

    [Fact]
    public void Ge01CountsTransactionSetsAndGe02EchoesGs06()
    {
        string edi = TestData.MinimalEdi();

        Assert.Equal("GE*1*1", TestData.RawSegment(edi, "GE"));
    }

    [Fact]
    public void Iea01CountsGroupsAndIea02EchoesIsa13()
    {
        string edi = TestData.MinimalEdi();

        Assert.Equal("IEA*1*000000001", TestData.RawSegment(edi, "IEA"));
    }

    [Fact]
    public void EnvelopeCountsAreRecomputedWhenTheBodyChanges()
    {
        // The writer owns the counts, so adding a segment cannot desynchronise them.
        var five = TestData.Envelope(
            new X12Segment("BHT", "0019"),
            new X12Segment("HL", "1", "", "20", "1"),
            new X12Segment("SBR", "P"));

        var six = TestData.Envelope(
            new X12Segment("BHT", "0019"),
            new X12Segment("HL", "1", "", "20", "1"),
            new X12Segment("SBR", "P"),
            new X12Segment("HI", X12Element.Composite("ABK", "J189")));

        var writer = new X12Writer();

        Assert.Equal("SE*5*0001", TestData.RawSegment(writer.Write(five), "SE"));
        Assert.Equal("SE*6*0001", TestData.RawSegment(writer.Write(six), "SE"));
    }

    [Fact]
    public void CompositeElementsUseTheInterchangesComponentSeparator()
    {
        var interchange = TestData.Envelope(
            new X12Segment("HI", X12Element.Composite("ABK", "J189")));

        Assert.Equal("HI*ABK:J189", TestData.RawSegment(new X12Writer().Write(interchange), "HI"));

        var delimiters = new X12Delimiters('|', '>', '+', '\n');
        string exotic = new X12Writer(delimiters).Write(interchange);

        Assert.Equal("HI|ABK>J189", TestData.RawSegment(exotic, "HI", delimiters));
    }

    [Fact]
    public void TrailingEmptyElementsAreSuppressed()
    {
        // X12 requires trailing empty elements to be omitted, and a partner counting elements
        // treats "NM1*41*2*NAME*****" and "NM1*41*2*NAME" as the same segment.
        var interchange = TestData.Envelope(new X12Segment("REF", "EI", "581234567", "", ""));

        Assert.Equal("REF*EI*581234567", TestData.RawSegment(new X12Writer().Write(interchange), "REF"));
    }

    [Fact]
    public void InteriorEmptyElementsAreKept()
    {
        // HL02 is empty for the top of the hierarchy. Suppressing it would shift HL03 into HL02
        // and the billing provider would be read as a parent id.
        var interchange = TestData.Envelope(new X12Segment("HL", "1", "", "20", "1"));

        Assert.Equal("HL*1**20*1", TestData.RawSegment(new X12Writer().Write(interchange), "HL"));
    }

    [Fact]
    public void AValueContainingADelimiterIsRejectedByDefault()
    {
        var interchange = TestData.Envelope(
            new X12Segment("NM1", "IL", "1", "O*BRIEN", "IXEQUI"));

        X12EncodingException error = Assert.Throws<X12EncodingException>(
            () => new X12Writer().Write(interchange));

        Assert.Equal(X12ErrorCode.DelimiterInData, error.Code);
        Assert.Equal("NM1", error.SegmentId);
        Assert.Contains("NM103", error.Message, StringComparison.Ordinal);
        Assert.Contains("no escape sequence", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void TheStripPolicyRemovesTheDelimiterInstead()
    {
        var interchange = TestData.Envelope(
            new X12Segment("NM1", "IL", "1", "O*BRIEN", "IXEQUI"));

        string edi = new X12Writer(X12Delimiters.Default, X12DelimiterPolicy.Strip).Write(interchange);

        Assert.Equal("NM1*IL*1*OBRIEN*IXEQUI", TestData.RawSegment(edi, "NM1"));
    }

    [Fact]
    public void AValueIsOnlyForbiddenIfItContainsThisInterchangesDelimiters()
    {
        // The same name is perfectly writable when the partner uses pipes.
        var interchange = TestData.Envelope(
            new X12Segment("NM1", "IL", "1", "O*BRIEN", "IXEQUI"));

        var delimiters = new X12Delimiters('|', '>', '+', '\n');
        string edi = new X12Writer(delimiters).Write(interchange);

        Assert.Equal("NM1|IL|1|O*BRIEN|IXEQUI", TestData.RawSegment(edi, "NM1", delimiters));
    }

    [Fact]
    public void AnIsaElementLongerThanItsFixedWidthIsRejected()
    {
        var interchange = TestData.Envelope(new X12Segment("BHT", "0019")) with
        {
            Header = TestData.Header() with { SenderId = "THIS-ID-IS-FAR-TOO-LONG" },
        };

        X12EncodingException error = Assert.Throws<X12EncodingException>(
            () => new X12Writer().Write(interchange));

        Assert.Equal(X12ErrorCode.FieldTooLong, error.Code);
        Assert.Contains("ISA06", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AnEnvelopeSegmentInsideTheBodyIsRejected()
    {
        // Supplying ST or SE by hand is how counts and control numbers drift apart.
        var interchange = TestData.Envelope(
            new X12Segment("BHT", "0019"),
            new X12Segment("SE", "99", "0001"));

        X12EncodingException error = Assert.Throws<X12EncodingException>(
            () => new X12Writer().Write(interchange));

        Assert.Equal(X12ErrorCode.EnvelopeSegmentInBody, error.Code);
    }

    [Fact]
    public void St03IsOmittedWhenNoImplementationGuideIsGiven()
    {
        var interchange = new X12Interchange(
            TestData.Header(),
            new[]
            {
                new X12FunctionalGroup(
                    TestData.GroupHeader(),
                    new[]
                    {
                        new X12TransactionSet("837", "0001", null, new[] { new X12Segment("BHT", "0019") }),
                    }),
            });

        Assert.Equal("ST*837*0001", TestData.RawSegment(new X12Writer().Write(interchange), "ST"));
    }
}
