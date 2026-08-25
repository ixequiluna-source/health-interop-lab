using System;
using System.Globalization;
using System.Text;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// Converts canonical ISO-8601-ish timestamps into the X12 date and time formats.
/// </summary>
/// <remarks>
/// <para>
/// The upstream HL7 service deliberately preserves partial dates: <c>1974</c>, <c>1974-03</c> and
/// <c>1974-03-14</c> are three different statements about what the sender actually knew. X12 has no
/// partial date. The correct response is to refuse to invent the missing precision — padding
/// <c>1974-03</c> to <c>19740301</c> fabricates a birthday, and a fabricated birthday is a real
/// eligibility mismatch at the payer and a real age in whatever clinical system reads the claim
/// back.
/// </para>
/// <para>
/// So these helpers return null rather than guessing, and the caller decides whether the affected
/// segment is required (fail the claim) or optional (omit the segment).
/// </para>
/// </remarks>
public static class X12Dates
{
    /// <summary>
    /// Converts to a D8 date (<c>CCYYMMDD</c>), or null when the input does not carry a full,
    /// valid calendar date.
    /// </summary>
    public static string? ToDate8(string? value)
    {
        string digits = Digits(value, 8);
        if (digits.Length < 8)
        {
            return null;
        }

        string candidate = digits.Substring(0, 8);
        return DateTime.TryParseExact(
            candidate,
            "yyyyMMdd",
            CultureInfo.InvariantCulture,
            DateTimeStyles.None,
            out _)
            ? candidate
            : null;
    }

    /// <summary>
    /// Converts to a DT date-time (<c>CCYYMMDDHHMM</c>), or null when the input does not carry a
    /// full date and a time.
    /// </summary>
    public static string? ToDateTime12(string? value)
    {
        string digits = Digits(value, 12);
        if (digits.Length < 12)
        {
            return null;
        }

        string candidate = digits.Substring(0, 12);
        return DateTime.TryParseExact(
            candidate,
            "yyyyMMddHHmm",
            CultureInfo.InvariantCulture,
            DateTimeStyles.None,
            out _)
            ? candidate
            : null;
    }

    /// <summary>
    /// Extracts up to <paramref name="wanted"/> leading digits, ignoring separators.
    /// </summary>
    /// <remarks>
    /// Taking digits positionally rather than parsing with a fixed format accepts every shape the
    /// upstream service can produce — <c>2026-08-25</c>, <c>2026-08-25T14:30:00</c>,
    /// <c>2026-08-25T14:30:00+02:00</c> and the bare HL7 <c>20260825143000</c> — while still
    /// failing closed on a partial date, because a partial date simply does not have enough digits.
    /// The offset's own digits can only ever appear after the twelve we care about.
    /// </remarks>
    private static string Digits(string? value, int wanted)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }

        var builder = new StringBuilder(wanted);
        foreach (char c in value)
        {
            if (c >= '0' && c <= '9')
            {
                builder.Append(c);
                if (builder.Length == wanted)
                {
                    break;
                }
            }
        }

        return builder.ToString();
    }
}

/// <summary>
/// Formats numbers for X12 R (decimal) elements.
/// </summary>
public static class X12Numbers
{
    /// <summary>
    /// Formats a monetary amount.
    /// </summary>
    /// <remarks>
    /// <para>
    /// Two decisions here, both of which have bitten real submitters.
    /// </para>
    /// <para>
    /// <see cref="CultureInfo.InvariantCulture"/> is not optional. .NET formats decimals with the
    /// current culture by default, so the same build running on a host with a European locale emits
    /// <c>125,50</c>. In a comma-delimited interchange that is two elements; in a star-delimited one
    /// it is simply an unparseable amount, and the claim is rejected at the clearinghouse.
    /// </para>
    /// <para>
    /// <see cref="MidpointRounding.AwayFromZero"/> is not optional either. .NET's default is
    /// banker's rounding, which turns 0.125 into 0.12. Cent-level differences between the line
    /// charges and CLM02 are a hard edit at most payers, because CLM02 must equal the sum of the
    /// SV102 amounts exactly.
    /// </para>
    /// </remarks>
    public static string Money(decimal amount) =>
        decimal.Round(amount, 2, MidpointRounding.AwayFromZero).ToString("0.##", CultureInfo.InvariantCulture);

    /// <summary>Formats a service quantity (SV104), which may be fractional for anaesthesia units.</summary>
    public static string Quantity(decimal quantity) =>
        decimal.Round(quantity, 3, MidpointRounding.AwayFromZero).ToString("0.###", CultureInfo.InvariantCulture);
}
