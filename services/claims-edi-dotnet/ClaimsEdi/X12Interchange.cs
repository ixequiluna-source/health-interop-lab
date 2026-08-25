using System.Collections.Generic;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// The data carried by an ISA segment.
/// </summary>
/// <remarks>
/// <para>
/// ISA11 (repetition separator) and ISA16 (component separator) are deliberately absent. They are
/// not data about the interchange; they are the interchange's own description of its encoding, and
/// they live on <see cref="X12Delimiters"/>. Keeping them out of the header model means it is
/// impossible to write an interchange whose declared ISA16 disagrees with the separator actually
/// used between components — a defect that is invisible until a partner's translator believes the
/// ISA and every composite in the file reads as one component.
/// </para>
/// <para>
/// Values here are unpadded. The fixed-width padding is applied on the way out by
/// <see cref="X12Writer"/> and stripped on the way in by <see cref="X12Reader"/>, so a sender id of
/// <c>FIRMUSHEALTH</c> compares equal to itself after a round trip instead of acquiring three
/// trailing spaces that then fail an equality check against the trading partner configuration.
/// </para>
/// </remarks>
/// <param name="AuthorizationQualifier">ISA01, 2 chars. <c>00</c> = no authorization information.</param>
/// <param name="AuthorizationInformation">ISA02, 10 chars, usually blank.</param>
/// <param name="SecurityQualifier">ISA03, 2 chars. <c>00</c> = no security information.</param>
/// <param name="SecurityInformation">ISA04, 10 chars, usually blank.</param>
/// <param name="SenderQualifier">ISA05, 2 chars. <c>ZZ</c> = mutually defined.</param>
/// <param name="SenderId">ISA06, 15 chars.</param>
/// <param name="ReceiverQualifier">ISA07, 2 chars.</param>
/// <param name="ReceiverId">ISA08, 15 chars.</param>
/// <param name="Date">ISA09, YYMMDD. Two-digit year: the ISA predates the fix and was never widened.</param>
/// <param name="Time">ISA10, HHMM.</param>
/// <param name="VersionNumber">ISA12, 5 chars. <c>00501</c> for 005010.</param>
/// <param name="ControlNumber">ISA13, up to 9 digits. Must be echoed by IEA02.</param>
/// <param name="AcknowledgmentRequested">ISA14. <c>0</c> = no TA1 requested, <c>1</c> = requested.</param>
/// <param name="UsageIndicator">ISA15. <c>T</c> = test, <c>P</c> = production.</param>
public sealed record X12InterchangeHeader(
    string AuthorizationQualifier,
    string AuthorizationInformation,
    string SecurityQualifier,
    string SecurityInformation,
    string SenderQualifier,
    string SenderId,
    string ReceiverQualifier,
    string ReceiverId,
    string Date,
    string Time,
    string VersionNumber,
    string ControlNumber,
    string AcknowledgmentRequested,
    string UsageIndicator);

/// <summary>
/// The data carried by a GS segment.
/// </summary>
/// <param name="FunctionalIdentifierCode">GS01. <c>HC</c> for health care claims (837).</param>
/// <param name="ApplicationSenderCode">GS02.</param>
/// <param name="ApplicationReceiverCode">GS03.</param>
/// <param name="Date">GS04, CCYYMMDD. Four-digit year, unlike ISA09.</param>
/// <param name="Time">GS05, HHMM.</param>
/// <param name="ControlNumber">GS06. Must be echoed by GE02.</param>
/// <param name="ResponsibleAgencyCode">GS07. <c>X</c> = Accredited Standards Committee X12.</param>
/// <param name="VersionReleaseCode">GS08. <c>005010X222A1</c> for the 837P implementation guide.</param>
public sealed record X12GroupHeader(
    string FunctionalIdentifierCode,
    string ApplicationSenderCode,
    string ApplicationReceiverCode,
    string Date,
    string Time,
    string ControlNumber,
    string ResponsibleAgencyCode,
    string VersionReleaseCode);

/// <summary>
/// One ST/SE transaction set.
/// </summary>
/// <remarks>
/// <see cref="Segments"/> holds only the body — the segments strictly between ST and SE. ST and SE
/// themselves are synthesised by the writer so that SE01 (the segment count) and SE02 (the echo of
/// ST02) cannot be supplied wrongly by a caller. A count that a caller can set is a count that
/// eventually disagrees with reality.
/// </remarks>
/// <param name="IdentifierCode">ST01. <c>837</c>.</param>
/// <param name="ControlNumber">ST02, unique within the functional group.</param>
/// <param name="ImplementationReference">ST03, the implementation guide id. Null when not sent.</param>
/// <param name="Segments">The transaction set body, excluding ST and SE.</param>
public sealed record X12TransactionSet(
    string IdentifierCode,
    string ControlNumber,
    string? ImplementationReference,
    IReadOnlyList<X12Segment> Segments);

/// <summary>One GS/GE functional group.</summary>
public sealed record X12FunctionalGroup(
    X12GroupHeader Header,
    IReadOnlyList<X12TransactionSet> TransactionSets);

/// <summary>
/// One ISA/IEA interchange.
/// </summary>
/// <remarks>
/// <see cref="Delimiters"/> travels with the interchange because an interchange read from a partner
/// must be answered in the encoding the partner used. Defaulting it here rather than making it a
/// constructor parameter keeps the builder path simple while still forcing the reader to record
/// what it actually saw.
/// </remarks>
public sealed record X12Interchange(
    X12InterchangeHeader Header,
    IReadOnlyList<X12FunctionalGroup> Groups)
{
    /// <summary>The encoding this interchange was read with, or should be written with.</summary>
    public X12Delimiters Delimiters { get; init; } = X12Delimiters.Default;

    /// <summary>Total number of transaction sets across every functional group.</summary>
    public int TransactionSetCount
    {
        get
        {
            int total = 0;
            foreach (X12FunctionalGroup group in Groups)
            {
                total += group.TransactionSets.Count;
            }

            return total;
        }
    }
}
