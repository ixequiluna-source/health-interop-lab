using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class X12DatesTests
{
    [Theory]
    [InlineData("2026-08-25", "20260825")]
    [InlineData("2026-08-25T14:30:00", "20260825")]
    [InlineData("2026-08-25T14:30:00Z", "20260825")]
    [InlineData("2026-08-25T14:30:00+02:00", "20260825")]
    [InlineData("20260825143000", "20260825")]
    public void FullDatesConvertToD8(string input, string expected)
    {
        Assert.Equal(expected, X12Dates.ToDate8(input));
    }

    [Theory]
    [InlineData("1974-03")]
    [InlineData("1974")]
    [InlineData("")]
    public void PartialDatesAreRefusedRatherThanPadded(string input)
    {
        // Widening 1974-03 to 19740301 invents a birthday. The caller decides whether that means
        // "omit the segment" or "fail the claim"; this helper does not decide it by guessing.
        Assert.Null(X12Dates.ToDate8(input));
    }

    [Fact]
    public void AnAbsentDateIsRefused()
    {
        Assert.Null(X12Dates.ToDate8(null));
        Assert.Null(X12Dates.ToDateTime12(null));
    }

    [Fact]
    public void ImpossibleCalendarDatesAreRefused()
    {
        Assert.Null(X12Dates.ToDate8("2026-02-31"));
        Assert.Null(X12Dates.ToDate8("2026-13-01"));
    }

    [Theory]
    [InlineData("2026-08-25T14:30:00Z", "202608251430")]
    [InlineData("2026-08-25T14:30:00+02:00", "202608251430")]
    [InlineData("20260825143000", "202608251430")]
    public void FullTimestampsConvertToDt(string input, string expected)
    {
        // The offset's own digits can only appear after the twelve that matter, so extracting
        // positionally is safe and accepts every shape the upstream service emits.
        Assert.Equal(expected, X12Dates.ToDateTime12(input));
    }

    [Fact]
    public void ADateWithNoTimeCannotBecomeADt()
    {
        Assert.Null(X12Dates.ToDateTime12("2026-08-25"));
    }
}

public sealed class X12NumbersTests
{
    [Theory]
    [InlineData("425.50", "425.5")]
    [InlineData("74.25", "74.25")]
    [InlineData("1000", "1000")]
    [InlineData("0", "0")]
    public void MoneyIsWrittenWithoutInsignificantZeros(string amount, string expected)
    {
        Assert.Equal(expected, X12Numbers.Money(decimal.Parse(amount, System.Globalization.CultureInfo.InvariantCulture)));
    }

    [Fact]
    public void MoneyRoundsAwayFromZeroRatherThanToEven()
    {
        // .NET's default is banker's rounding, which would make this 0.12 and under-bill by a cent.
        // Cent-level drift between the line charges and CLM02 is a hard payer edit.
        Assert.Equal("0.13", X12Numbers.Money(0.125m));
        Assert.Equal("0.14", X12Numbers.Money(0.135m));
    }

    [Fact]
    public void MoneyUsesTheInvariantDecimalSeparator()
    {
        // A build host with a European locale would otherwise emit "125,50", which no
        // clearinghouse will parse.
        Assert.Equal("125.5", X12Numbers.Money(125.50m));
        Assert.DoesNotContain(",", X12Numbers.Money(1234567.89m), System.StringComparison.Ordinal);
    }

    [Fact]
    public void QuantitiesKeepUpToThreeDecimals()
    {
        Assert.Equal("1", X12Numbers.Quantity(1m));
        Assert.Equal("2.5", X12Numbers.Quantity(2.5m));
        Assert.Equal("0.333", X12Numbers.Quantity(0.3334m));
    }
}
