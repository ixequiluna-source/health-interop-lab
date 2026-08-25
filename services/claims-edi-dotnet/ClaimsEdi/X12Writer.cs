using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// What to do with a value that contains a character X12 cannot represent.
/// </summary>
/// <remarks>
/// <para>
/// X12 has no escape mechanism. There is no equivalent of HL7's <c>\F\</c>; a <c>*</c> inside a
/// patient's name simply cannot be written. The three available behaviours are: emit it anyway,
/// strip it, or refuse. Emitting it is never correct — the partner's translator counts separators,
/// so a surname of <c>O*BRIEN</c> shifts every later element by one and the payer reads the total
/// charge out of the element that should have held the subscriber identifier. That failure is
/// silent, it produces a syntactically valid claim, and it is discovered weeks later in
/// remittance.
/// </para>
/// <para>
/// The default here is <see cref="Reject"/>. A claim we cannot encode is a data-quality problem
/// upstream, and turning it into a loud, named error at the boundary is worth more than a
/// submitted claim with a quietly mangled name. <see cref="Strip"/> exists because some
/// clearinghouse contracts require best-effort submission of the whole batch, and stripping a
/// separator from a name is at least a decision someone made on purpose.
/// </para>
/// </remarks>
public enum X12DelimiterPolicy
{
    /// <summary>Throw <see cref="X12EncodingException"/> naming the segment, element and character.</summary>
    Reject = 0,

    /// <summary>Remove the offending characters and write the remainder.</summary>
    Strip = 1,
}

/// <summary>
/// Serialises an <see cref="X12Interchange"/>.
/// </summary>
/// <remarks>
/// Every count and every control-number echo in the envelope is computed here rather than accepted
/// from the caller: SE01 from the number of body segments, SE02 from ST02, GE01 from the number of
/// transaction sets, GE02 from GS06, IEA01 from the number of groups and IEA02 from ISA13. These
/// six linkages are what a trading partner validates before it looks at a single claim, and they
/// are also the six things that drift the moment somebody adds a segment to a builder and forgets
/// to bump a counter.
/// </remarks>
public sealed class X12Writer
{
    private static readonly string[] EnvelopeSegmentIds = { "ISA", "GS", "ST", "SE", "GE", "IEA" };

    private readonly X12Delimiters _delimiters;
    private readonly X12DelimiterPolicy _policy;

    public X12Writer(X12Delimiters delimiters, X12DelimiterPolicy policy = X12DelimiterPolicy.Reject)
    {
        _delimiters = delimiters ?? throw new ArgumentNullException(nameof(delimiters));
        _policy = policy;
    }

    /// <summary>Convenience writer using the conventional <c>* : ^ ~</c> set.</summary>
    public X12Writer()
        : this(X12Delimiters.Default)
    {
    }

    /// <summary>The encoding this writer emits.</summary>
    public X12Delimiters Delimiters => _delimiters;

    /// <summary>The policy applied to values containing delimiters.</summary>
    public X12DelimiterPolicy Policy => _policy;

    /// <summary>Serialises an interchange to its wire form.</summary>
    /// <exception cref="X12EncodingException">
    /// A value contains a delimiter and the policy is <see cref="X12DelimiterPolicy.Reject"/>, an
    /// ISA element exceeds its fixed width, or a transaction set body contains an envelope segment.
    /// </exception>
    public string Write(X12Interchange interchange)
    {
        if (interchange is null)
        {
            throw new ArgumentNullException(nameof(interchange));
        }

        var builder = new StringBuilder(1024);
        WriteIsa(builder, interchange.Header);

        foreach (X12FunctionalGroup group in interchange.Groups)
        {
            X12GroupHeader header = group.Header;
            WriteSegment(builder, new X12Segment(
                "GS",
                header.FunctionalIdentifierCode,
                header.ApplicationSenderCode,
                header.ApplicationReceiverCode,
                header.Date,
                header.Time,
                header.ControlNumber,
                header.ResponsibleAgencyCode,
                header.VersionReleaseCode));

            foreach (X12TransactionSet set in group.TransactionSets)
            {
                WriteTransactionSet(builder, set);
            }

            // GE01 counts transaction sets; GE02 echoes GS06 verbatim, zero padding included, so
            // that a partner comparing the two as strings rather than as numbers still agrees.
            WriteSegment(builder, new X12Segment(
                "GE",
                group.TransactionSets.Count.ToString(CultureInfo.InvariantCulture),
                header.ControlNumber));
        }

        // IEA01 counts functional groups; IEA02 echoes ISA13. ISA13 is written zero-filled to nine
        // characters, and we echo that rendering rather than the trimmed number for the same
        // reason: some translators compare ordinally.
        WriteSegment(builder, new X12Segment(
            "IEA",
            interchange.Groups.Count.ToString(CultureInfo.InvariantCulture),
            FitIsaElement(interchange.Header.ControlNumber, 13)));

        return builder.ToString();
    }

    private void WriteTransactionSet(StringBuilder builder, X12TransactionSet set)
    {
        foreach (X12Segment segment in set.Segments)
        {
            foreach (string envelopeId in EnvelopeSegmentIds)
            {
                if (string.Equals(segment.Id, envelopeId, StringComparison.Ordinal))
                {
                    throw new X12EncodingException(
                        X12ErrorCode.EnvelopeSegmentInBody,
                        $"A transaction set body must not contain the envelope segment '{segment.Id}'; the writer owns the envelope so that its counts and control numbers cannot disagree with its contents.",
                        0,
                        segment.Id);
                }
            }
        }

        WriteSegment(builder, new X12Segment(
            "ST",
            set.IdentifierCode,
            set.ControlNumber,
            set.ImplementationReference ?? string.Empty));

        foreach (X12Segment segment in set.Segments)
        {
            WriteSegment(builder, segment);
        }

        // SE01 is the segment count of the transaction set INCLUDING ST and SE. Counting only the
        // body — or only the body plus ST — is the single most common 837 rejection there is, and
        // it is rejected at the envelope, so nothing in the claim is ever looked at.
        int segmentCount = set.Segments.Count + 2;

        WriteSegment(builder, new X12Segment(
            "SE",
            segmentCount.ToString(CultureInfo.InvariantCulture),
            set.ControlNumber));
    }

    /// <summary>
    /// Writes the ISA. This is the only fixed-width segment in X12 and the only one written by
    /// offset rather than by delimiter.
    /// </summary>
    private void WriteIsa(StringBuilder builder, X12InterchangeHeader header)
    {
        // Element 11 and element 16 are the encoding itself, taken from the delimiter set rather
        // than from the header, so the ISA can never lie about how the rest of the file is encoded.
        string[] values =
        {
            header.AuthorizationQualifier,
            header.AuthorizationInformation,
            header.SecurityQualifier,
            header.SecurityInformation,
            header.SenderQualifier,
            header.SenderId,
            header.ReceiverQualifier,
            header.ReceiverId,
            header.Date,
            header.Time,
            _delimiters.Repetition.ToString(),
            header.VersionNumber,
            header.ControlNumber,
            header.AcknowledgmentRequested,
            header.UsageIndicator,
            _delimiters.Component.ToString(),
        };

        builder.Append("ISA");
        for (int i = 0; i < X12IsaLayout.ElementCount; i++)
        {
            int elementNumber = i + 1;
            builder.Append(_delimiters.Element);

            if (elementNumber == X12IsaLayout.RepetitionSeparatorElement
                || elementNumber == X12IsaLayout.ComponentSeparatorElement)
            {
                // ISA11 and ISA16 are the only elements in the whole standard whose value is
                // legitimately a delimiter, so they bypass the forbidden-character check that
                // every other element goes through. Both are exactly one character wide.
                builder.Append(values[i]);
                continue;
            }

            builder.Append(FitIsaElement(values[i], elementNumber));
        }

        builder.Append(_delimiters.Segment);
    }

    /// <summary>
    /// Pads or validates one ISA element to its exact fixed width.
    /// </summary>
    private string FitIsaElement(string? raw, int elementNumber)
    {
        string value = Sanitize(raw ?? string.Empty, "ISA", elementNumber);
        int width = X12IsaLayout.ElementWidth(elementNumber);

        if (value.Length > width)
        {
            throw new X12EncodingException(
                X12ErrorCode.FieldTooLong,
                $"ISA{elementNumber.ToString("00", CultureInfo.InvariantCulture)} is fixed at {width.ToString(CultureInfo.InvariantCulture)} characters; '{value}' is {value.Length.ToString(CultureInfo.InvariantCulture)}. Truncating it would change the interchange's identity, so it is refused instead.",
                1,
                "ISA");
        }

        return X12IsaLayout.ElementPadding(elementNumber) switch
        {
            // AN fields are left justified and space filled: an "empty" ISA02 is ten spaces.
            X12IsaLayout.Padding.SpaceRight => value.PadRight(width, ' '),

            // ISA13 is N0, right justified and zero filled. Writing "1" instead of "000000001"
            // produces a 98-character ISA, which is the classic ISA-length rejection.
            X12IsaLayout.Padding.ZeroLeft => value.PadLeft(width, '0'),

            // ID, DT and TM elements have no padding rule: the standard fixes their length, so a
            // short value is a wrong value and padding it would only hide that.
            _ => value.Length == width
                ? value
                : throw new X12EncodingException(
                    X12ErrorCode.FieldTooLong,
                    $"ISA{elementNumber.ToString("00", CultureInfo.InvariantCulture)} must be exactly {width.ToString(CultureInfo.InvariantCulture)} characters; '{value}' is {value.Length.ToString(CultureInfo.InvariantCulture)}. This element is not padded by the standard, so a short value is a data error rather than a formatting one.",
                    1,
                    "ISA"),
        };
    }

    private void WriteSegment(StringBuilder builder, X12Segment segment)
    {
        builder.Append(Sanitize(segment.Id, segment.Id, 0));

        for (int elementIndex = 0; elementIndex < segment.ElementCount; elementIndex++)
        {
            builder.Append(_delimiters.Element);
            X12Element element = segment.Elements[elementIndex];

            for (int componentIndex = 0; componentIndex < element.ComponentCount; componentIndex++)
            {
                if (componentIndex > 0)
                {
                    builder.Append(_delimiters.Component);
                }

                builder.Append(Sanitize(element.Components[componentIndex], segment.Id, elementIndex + 1));
            }
        }

        builder.Append(_delimiters.Segment);
    }

    private string Sanitize(string value, string segmentId, int elementNumber)
    {
        int offset = _delimiters.IndexOfForbidden(value);
        if (offset < 0)
        {
            return value;
        }

        if (_policy == X12DelimiterPolicy.Strip)
        {
            return _delimiters.RemoveForbidden(value);
        }

        string position = elementNumber > 0
            ? $"{segmentId}{elementNumber.ToString("00", CultureInfo.InvariantCulture)}"
            : $"the {segmentId} segment identifier";

        throw new X12EncodingException(
            X12ErrorCode.DelimiterInData,
            $"{position} contains '{X12Delimiters.Describe(value[offset])}' at offset {offset.ToString(CultureInfo.InvariantCulture)}, which is a delimiter in this interchange. X12 has no escape sequence, so writing it would shift every following element and silently corrupt the claim.",
            0,
            segmentId);
    }
}
