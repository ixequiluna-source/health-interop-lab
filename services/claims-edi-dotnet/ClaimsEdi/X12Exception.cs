using System;
using System.Globalization;
using System.Text;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// What went wrong, as a code a caller can branch on.
/// </summary>
/// <remarks>
/// A trading partner integration is a support workload before it is an engineering one. "Object
/// reference not set to an instance of an object" tells the person on the phone nothing;
/// <c>SegmentCountMismatch at segment 31 (SE)</c> tells them exactly which segment to look at in
/// the file the partner sent back.
/// </remarks>
public enum X12ErrorCode
{
    None = 0,

    /// <summary>The input contained no segments.</summary>
    EmptyInterchange,

    /// <summary>The input does not begin with ISA.</summary>
    MissingIsa,

    /// <summary>The ISA segment is not exactly 106 characters.</summary>
    IsaLength,

    /// <summary>The characters at the ISA's delimiter offsets cannot be used as delimiters.</summary>
    InvalidDelimiters,

    /// <summary>A segment appeared where the envelope grammar does not allow it.</summary>
    UnexpectedSegment,

    /// <summary>A required closing segment (SE, GE, IEA) is absent.</summary>
    MissingSegment,

    /// <summary>An envelope level was opened and never closed, or closed twice.</summary>
    UnbalancedEnvelope,

    /// <summary>SE02/ST02, GE02/GS06 or IEA02/ISA13 disagree.</summary>
    ControlNumberMismatch,

    /// <summary>SE01 does not match the number of segments in the transaction set.</summary>
    SegmentCountMismatch,

    /// <summary>GE01 does not match the number of transaction sets in the group.</summary>
    TransactionSetCountMismatch,

    /// <summary>IEA01 does not match the number of functional groups in the interchange.</summary>
    FunctionalGroupCountMismatch,

    /// <summary>An element value contains a delimiter, which X12 cannot escape.</summary>
    DelimiterInData,

    /// <summary>A fixed-width ISA element was given a value longer than its width.</summary>
    FieldTooLong,

    /// <summary>A caller-supplied envelope segment was found in a transaction set body.</summary>
    EnvelopeSegmentInBody,

    /// <summary>The canonical event does not carry data a claim requires.</summary>
    MissingRequiredData,

    /// <summary>A date or amount in the canonical event cannot be represented in X12.</summary>
    UnrepresentableValue,
}

/// <summary>
/// Base type for everything this library refuses to do, carrying the position of the offending
/// segment so the message points at a line in a file rather than at a stack frame.
/// </summary>
public class X12Exception : Exception
{
    public X12Exception(
        X12ErrorCode code,
        string detail,
        int segmentPosition = 0,
        string? segmentId = null,
        Exception? innerException = null)
        : base(Compose(code, detail, segmentPosition, segmentId), innerException)
    {
        Code = code;
        Detail = detail;
        SegmentPosition = segmentPosition;
        SegmentId = segmentId;
    }

    /// <summary>Machine-readable classification.</summary>
    public X12ErrorCode Code { get; }

    /// <summary>The message without the position prefix.</summary>
    public string Detail { get; }

    /// <summary>
    /// 1-based ordinal of the segment within the interchange, counting ISA as 1. Zero when the
    /// failure is not attributable to a specific segment.
    /// </summary>
    public int SegmentPosition { get; }

    /// <summary>Identifier of the offending segment, when known.</summary>
    public string? SegmentId { get; }

    private static string Compose(X12ErrorCode code, string detail, int segmentPosition, string? segmentId)
    {
        var builder = new StringBuilder(64);
        builder.Append('[').Append(code.ToString()).Append(']');

        if (segmentPosition > 0)
        {
            builder.Append(" segment ").Append(segmentPosition.ToString(CultureInfo.InvariantCulture));
            if (!string.IsNullOrEmpty(segmentId))
            {
                builder.Append(" (").Append(segmentId).Append(')');
            }
        }
        else if (!string.IsNullOrEmpty(segmentId))
        {
            builder.Append(' ').Append(segmentId);
        }

        return builder.Append(": ").Append(detail).ToString();
    }
}

/// <summary>Raised when an inbound interchange cannot be read or does not validate.</summary>
public sealed class X12ParseException : X12Exception
{
    public X12ParseException(
        X12ErrorCode code,
        string detail,
        int segmentPosition = 0,
        string? segmentId = null,
        Exception? innerException = null)
        : base(code, detail, segmentPosition, segmentId, innerException)
    {
    }
}

/// <summary>
/// Raised when a value cannot be written into X12 without corrupting the interchange — a delimiter
/// inside data, or an over-long fixed-width ISA element.
/// </summary>
public sealed class X12EncodingException : X12Exception
{
    public X12EncodingException(
        X12ErrorCode code,
        string detail,
        int segmentPosition = 0,
        string? segmentId = null,
        Exception? innerException = null)
        : base(code, detail, segmentPosition, segmentId, innerException)
    {
    }
}

/// <summary>
/// Raised when a canonical admission event cannot be turned into a claim — a missing identifier, a
/// partial date where X12 requires a full one.
/// </summary>
public sealed class X12MappingException : X12Exception
{
    public X12MappingException(
        X12ErrorCode code,
        string detail,
        Exception? innerException = null)
        : base(code, detail, 0, null, innerException)
    {
    }
}
