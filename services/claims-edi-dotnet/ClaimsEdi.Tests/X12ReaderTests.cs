using System;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class X12ReaderTests
{
    private static X12ParseException Rejects(string edi) =>
        Assert.Throws<X12ParseException>(() => new X12Reader().Read(edi));

    [Fact]
    public void AValidInterchangeParsesIntoItsEnvelope()
    {
        X12Interchange interchange = new X12Reader().Read(TestData.MinimalEdi());

        Assert.Equal("000000001", interchange.Header.ControlNumber);
        Assert.Single(interchange.Groups);
        Assert.Single(interchange.Groups[0].TransactionSets);
        Assert.Equal(3, TestData.OnlySet(interchange).Segments.Count);
        Assert.Equal("005010X222A1", TestData.OnlySet(interchange).ImplementationReference);
    }

    [Fact]
    public void IsaPaddingIsStrippedBecauseItIsEncodingRatherThanData()
    {
        X12Interchange interchange = new X12Reader().Read(TestData.MinimalEdi());

        // Leave the padding on and every comparison against the configured trading partner id
        // fails for a reason invisible in a text editor.
        Assert.Equal("FIRMUSHEALTH", interchange.Header.SenderId);
        Assert.Equal("CLEARINGHOUSE", interchange.Header.ReceiverId);
        Assert.Equal(string.Empty, interchange.Header.AuthorizationInformation);
    }

    [Fact]
    public void DelimitersAreTakenFromTheIsaRatherThanAssumed()
    {
        var delimiters = new X12Delimiters('|', '>', '+', '\n');
        string edi = TestData.MinimalEdi(delimiters);

        X12Interchange interchange = new X12Reader().Read(edi);

        Assert.Equal(delimiters, interchange.Delimiters);
        Assert.Equal("FIRMUSHEALTH", interchange.Header.SenderId);
    }

    [Fact]
    public void EmptyInputIsRejected()
    {
        Assert.Equal(X12ErrorCode.EmptyInterchange, Rejects("   ").Code);
    }

    [Fact]
    public void InputThatDoesNotBeginWithIsaIsRejected()
    {
        X12ParseException error = Rejects("GS*HC*FIRMUS01*CH0001*20260825*1430*1*X*005010X222A1~");

        Assert.Equal(X12ErrorCode.MissingIsa, error.Code);
        Assert.Contains("must begin with 'ISA'", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void InputShorterThanTheIsaIsRejected()
    {
        X12ParseException error = Rejects(TestData.MinimalEdi().Substring(0, 50));

        Assert.Equal(X12ErrorCode.IsaLength, error.Code);
        Assert.Equal(1, error.SegmentPosition);
        Assert.Contains("106", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AMisPaddedIsaIsRejectedWithAMessageAboutItsLength()
    {
        // Drop one of ISA06's padding spaces: the classic "we did not pad the sender id" defect.
        // Every downstream offset shifts, which is exactly why this is caught at the ISA and not
        // three segments later as a mysterious content error.
        string mangled = TestData.MinimalEdi().Remove(49, 1);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(1, error.SegmentPosition);
        Assert.Equal("ISA", error.SegmentId);
        Assert.Contains("106", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ASegmentCountThatDisagreesWithTheTransactionSetIsRejected()
    {
        string mangled = TestData.MinimalEdi().Replace("SE*5*0001", "SE*9*0001", StringComparison.Ordinal);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.SegmentCountMismatch, error.Code);
        Assert.Equal("SE", error.SegmentId);
        Assert.Equal(7, error.SegmentPosition); // ISA GS ST BHT NM1 SBR SE
        Assert.Contains("declares 9", error.Message, StringComparison.Ordinal);
        Assert.Contains("contains 5", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Se02ThatDoesNotEchoSt02IsRejected()
    {
        string mangled = TestData.MinimalEdi().Replace("SE*5*0001", "SE*5*0002", StringComparison.Ordinal);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.ControlNumberMismatch, error.Code);
        Assert.Contains("SE02", error.Message, StringComparison.Ordinal);
        Assert.Contains("ST02", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Ge02ThatDoesNotEchoGs06IsRejected()
    {
        string mangled = TestData.MinimalEdi().Replace("GE*1*1~", "GE*1*2~", StringComparison.Ordinal);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.ControlNumberMismatch, error.Code);
        Assert.Equal("GE", error.SegmentId);
    }

    [Fact]
    public void Ge01ThatDoesNotCountTheTransactionSetsIsRejected()
    {
        string mangled = TestData.MinimalEdi().Replace("GE*1*1~", "GE*2*1~", StringComparison.Ordinal);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.TransactionSetCountMismatch, error.Code);
    }

    [Fact]
    public void Iea02ThatDoesNotEchoIsa13IsRejected()
    {
        string mangled = TestData.MinimalEdi()
            .Replace("IEA*1*000000001", "IEA*1*000000002", StringComparison.Ordinal);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.ControlNumberMismatch, error.Code);
        Assert.Equal("IEA", error.SegmentId);
        Assert.Contains("IEA02", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AnUnpaddedIea02IsAcceptedBecauseBothFieldsAreNumeric()
    {
        // ISA13 is fixed width and zero filled; IEA02 is not. Comparing them as strings would
        // reject a perfectly valid interchange, which is a more expensive mistake than the
        // mismatch the check exists to find.
        string edi = TestData.MinimalEdi()
            .Replace("IEA*1*000000001", "IEA*1*1", StringComparison.Ordinal);

        X12Interchange interchange = new X12Reader().Read(edi);

        Assert.Equal("000000001", interchange.Header.ControlNumber);
    }

    [Fact]
    public void Iea01ThatDoesNotCountTheGroupsIsRejected()
    {
        string mangled = TestData.MinimalEdi()
            .Replace("IEA*1*000000001", "IEA*2*000000001", StringComparison.Ordinal);

        Assert.Equal(X12ErrorCode.FunctionalGroupCountMismatch, Rejects(mangled).Code);
    }

    [Fact]
    public void ATransactionSetThatIsNeverClosedIsRejected()
    {
        string mangled = TestData.MinimalEdi().Replace("SE*5*0001~", string.Empty, StringComparison.Ordinal);

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.UnbalancedEnvelope, error.Code);
        Assert.Equal("GE", error.SegmentId);
    }

    [Fact]
    public void AnInterchangeWithNoIeaIsRejected()
    {
        string edi = TestData.MinimalEdi();
        string mangled = edi.Substring(0, edi.LastIndexOf("IEA", StringComparison.Ordinal));

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.MissingSegment, error.Code);
        Assert.Contains("never closed by IEA", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void SegmentsAfterTheIeaAreRejected()
    {
        // Two interchanges concatenated into one file. Reading past the IEA would attribute the
        // second interchange's claims to the first interchange's control number.
        string mangled = TestData.MinimalEdi() + "ISA*00*~";

        X12ParseException error = Rejects(mangled);

        Assert.Equal(X12ErrorCode.UnexpectedSegment, error.Code);
        Assert.Contains("follow IEA", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void PrettyPrintedInterchangesParse()
    {
        // One segment per line is how most partners hand you a sample file.
        string edi = TestData.MinimalEdi().Replace("~", "~\r\n", StringComparison.Ordinal);

        X12Interchange interchange = new X12Reader().Read(edi);

        Assert.Equal(3, TestData.OnlySet(interchange).Segments.Count);
    }

    [Fact]
    public void ALeadingByteOrderMarkIsTolerated()
    {
        X12Interchange interchange = new X12Reader().Read("\uFEFF" + TestData.MinimalEdi());

        Assert.Equal("000000001", interchange.Header.ControlNumber);
    }

    [Fact]
    public void TheIsaIsNotComponentSplitSoIsa16SurvivesAsAnElement()
    {
        // ISA16's value is the component separator. Splitting ISA on it would leave the segment
        // with fifteen elements and the reader would reject a valid header.
        X12Interchange interchange = new X12Reader().Read(TestData.MinimalEdi());

        Assert.Equal("00501", interchange.Header.VersionNumber);
        Assert.Equal("T", interchange.Header.UsageIndicator);
    }
}
