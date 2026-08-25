using System;
using System.Collections.Generic;
using System.Globalization;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// Parses an X12 interchange and validates its envelope.
/// </summary>
/// <remarks>
/// <para>
/// The reader deliberately validates more than it needs to in order to build the object graph. A
/// clearinghouse rejects on the envelope long before it looks at a claim, so an interchange whose
/// SE01 is wrong is worthless even though every claim inside it is perfect. Validating those
/// linkages here means a regression in the builder is caught by our own reader in CI rather than
/// by a partner's 999 acknowledgement three days later.
/// </para>
/// <para>
/// Every failure is an <see cref="X12ParseException"/> carrying an <see cref="X12ErrorCode"/> and
/// the 1-based position of the offending segment, so support can open the file and go straight to
/// it.
/// </para>
/// </remarks>
public sealed class X12Reader
{
    /// <summary>Reads an interchange, validating the envelope as it goes.</summary>
    /// <exception cref="X12ParseException">The input is not a well-formed, self-consistent interchange.</exception>
    public X12Interchange Read(string text)
    {
        if (text is null)
        {
            throw new ArgumentNullException(nameof(text));
        }

        string body = TrimLeading(text);

        if (body.Length == 0)
        {
            throw new X12ParseException(
                X12ErrorCode.EmptyInterchange,
                "The input contains no data.");
        }

        if (!body.StartsWith("ISA", StringComparison.Ordinal))
        {
            throw new X12ParseException(
                X12ErrorCode.MissingIsa,
                $"An interchange must begin with 'ISA'; this one begins with '{Preview(body, 8)}'.",
                1);
        }

        if (body.Length < X12IsaLayout.SegmentLength)
        {
            throw new X12ParseException(
                X12ErrorCode.IsaLength,
                $"The ISA segment is fixed at {X12IsaLayout.SegmentLength.ToString(CultureInfo.InvariantCulture)} characters, but the whole input is only {body.Length.ToString(CultureInfo.InvariantCulture)}.",
                1,
                "ISA");
        }

        X12Delimiters delimiters = ReadDelimiters(body);

        // The ISA is fixed width, so the first segment terminator must land on offset 105 and
        // nowhere else. This one check catches the entire family of "a field was not padded"
        // defects, which is the most common reason a partner rejects a first submission.
        int terminatorOffset = body.IndexOf(delimiters.Segment);
        if (terminatorOffset != X12IsaLayout.SegmentTerminatorIndex)
        {
            string found = terminatorOffset < 0
                ? "it does not occur at all"
                : $"the first one is at offset {terminatorOffset.ToString(CultureInfo.InvariantCulture)}, making the ISA {(terminatorOffset + 1).ToString(CultureInfo.InvariantCulture)} characters";

            throw new X12ParseException(
                X12ErrorCode.IsaLength,
                $"The ISA segment must be exactly {X12IsaLayout.SegmentLength.ToString(CultureInfo.InvariantCulture)} characters including the terminator '{X12Delimiters.Describe(delimiters.Segment)}', but {found}. Check the fixed-width padding of ISA05 through ISA08 and the zero fill on ISA13.",
                1,
                "ISA");
        }

        IReadOnlyList<X12Segment> segments = SplitSegments(body, delimiters);
        return BuildInterchange(segments, delimiters);
    }

    /// <summary>
    /// Reads the four delimiters out of the ISA by byte offset.
    /// </summary>
    /// <remarks>
    /// This has to happen before any splitting, because splitting is not possible until the
    /// separators are known. It is the reason the ISA is fixed width in the first place.
    /// </remarks>
    private static X12Delimiters ReadDelimiters(string body)
    {
        char element = body[X12IsaLayout.ElementSeparatorIndex];
        char repetition = body[X12IsaLayout.RepetitionSeparatorIndex];
        char component = body[X12IsaLayout.ComponentSeparatorIndex];
        char segment = body[X12IsaLayout.SegmentTerminatorIndex];

        try
        {
            return new X12Delimiters(element, component, repetition, segment);
        }
        catch (ArgumentException ex)
        {
            throw new X12ParseException(
                X12ErrorCode.InvalidDelimiters,
                $"The characters at the ISA's delimiter offsets are not a usable delimiter set "
                + $"(offset {X12IsaLayout.ElementSeparatorIndex.ToString(CultureInfo.InvariantCulture)}='{X12Delimiters.Describe(element)}', "
                + $"{X12IsaLayout.RepetitionSeparatorIndex.ToString(CultureInfo.InvariantCulture)}='{X12Delimiters.Describe(repetition)}', "
                + $"{X12IsaLayout.ComponentSeparatorIndex.ToString(CultureInfo.InvariantCulture)}='{X12Delimiters.Describe(component)}', "
                + $"{X12IsaLayout.SegmentTerminatorIndex.ToString(CultureInfo.InvariantCulture)}='{X12Delimiters.Describe(segment)}'). "
                + $"This almost always means the ISA is not {X12IsaLayout.SegmentLength.ToString(CultureInfo.InvariantCulture)} characters, so the offsets land inside data. {ex.Message}",
                1,
                "ISA",
                ex);
        }
    }

    private static IReadOnlyList<X12Segment> SplitSegments(string body, X12Delimiters delimiters)
    {
        var segments = new List<X12Segment>();
        string[] parts = body.Split(delimiters.Segment);

        for (int i = 0; i < parts.Length; i++)
        {
            // Partners routinely pretty-print one segment per line. The line ending is formatting,
            // not data, so it is trimmed here — which is also why CR and LF are forbidden inside
            // element values on the way out.
            string raw = parts[i].Trim('\r', '\n');
            if (raw.Length == 0)
            {
                continue;
            }

            // The ISA is parsed without component splitting: ISA16's value *is* the component
            // separator, so splitting on it would turn the element into an empty one and the
            // segment would come back with 15 elements instead of 16.
            bool isIsa = segments.Count == 0;
            segments.Add(ParseSegment(raw, delimiters, splitComponents: !isIsa));
        }

        return segments;
    }

    private static X12Segment ParseSegment(string raw, X12Delimiters delimiters, bool splitComponents)
    {
        string[] tokens = raw.Split(delimiters.Element);
        string id = tokens[0];

        var elements = new X12Element[tokens.Length - 1];
        for (int i = 1; i < tokens.Length; i++)
        {
            string token = tokens[i];
            elements[i - 1] = splitComponents && token.IndexOf(delimiters.Component) >= 0
                ? X12Element.Composite(token.Split(delimiters.Component))
                : X12Element.Simple(token);
        }

        return new X12Segment(id, elements);
    }

    private static X12Interchange BuildInterchange(IReadOnlyList<X12Segment> segments, X12Delimiters delimiters)
    {
        X12Segment isa = segments[0];
        if (isa.ElementCount != X12IsaLayout.ElementCount)
        {
            throw new X12ParseException(
                X12ErrorCode.IsaLength,
                $"ISA carries {isa.ElementCount.ToString(CultureInfo.InvariantCulture)} elements; the standard fixes it at {X12IsaLayout.ElementCount.ToString(CultureInfo.InvariantCulture)}.",
                1,
                "ISA");
        }

        // Trailing spaces in ISA are padding, not data. Leaving them on turns a configured sender
        // id of "FIRMUSHEALTH" into "FIRMUSHEALTH   " and every downstream comparison fails.
        var header = new X12InterchangeHeader(
            isa.Value(1).TrimEnd(' '),
            isa.Value(2).TrimEnd(' '),
            isa.Value(3).TrimEnd(' '),
            isa.Value(4).TrimEnd(' '),
            isa.Value(5).TrimEnd(' '),
            isa.Value(6).TrimEnd(' '),
            isa.Value(7).TrimEnd(' '),
            isa.Value(8).TrimEnd(' '),
            isa.Value(9).TrimEnd(' '),
            isa.Value(10).TrimEnd(' '),
            isa.Value(12).TrimEnd(' '),
            isa.Value(13).TrimEnd(' '),
            isa.Value(14).TrimEnd(' '),
            isa.Value(15).TrimEnd(' '));

        var groups = new List<X12FunctionalGroup>();
        int index = 1;

        while (index < segments.Count && !IsId(segments[index], "IEA"))
        {
            X12Segment gs = segments[index];
            if (!IsId(gs, "GS"))
            {
                throw new X12ParseException(
                    X12ErrorCode.UnexpectedSegment,
                    $"Expected GS or IEA after the interchange header, found '{gs.Id}'.",
                    index + 1,
                    gs.Id);
            }

            var groupHeader = new X12GroupHeader(
                gs.Value(1),
                gs.Value(2),
                gs.Value(3),
                gs.Value(4),
                gs.Value(5),
                gs.Value(6),
                gs.Value(7),
                gs.Value(8));

            index++;
            var sets = new List<X12TransactionSet>();

            while (index < segments.Count && !IsId(segments[index], "GE"))
            {
                index = ReadTransactionSet(segments, index, groupHeader, sets);
            }

            if (index >= segments.Count)
            {
                throw new X12ParseException(
                    X12ErrorCode.MissingSegment,
                    $"Functional group {groupHeader.ControlNumber} was opened by GS but never closed by GE.",
                    segments.Count,
                    segments[segments.Count - 1].Id);
            }

            X12Segment ge = segments[index];
            int declaredSets = ReadCount(
                ge.Value(1), index + 1, "GE", "GE01", X12ErrorCode.TransactionSetCountMismatch);
            if (declaredSets != sets.Count)
            {
                throw new X12ParseException(
                    X12ErrorCode.TransactionSetCountMismatch,
                    $"GE01 declares {declaredSets.ToString(CultureInfo.InvariantCulture)} transaction set(s); the group contains {sets.Count.ToString(CultureInfo.InvariantCulture)}.",
                    index + 1,
                    "GE");
            }

            if (!NumericControlNumbersMatch(ge.Value(2), groupHeader.ControlNumber))
            {
                throw new X12ParseException(
                    X12ErrorCode.ControlNumberMismatch,
                    $"GE02 is '{ge.Value(2)}' but GS06 is '{groupHeader.ControlNumber}'; a functional group must be closed with the control number it was opened with.",
                    index + 1,
                    "GE");
            }

            groups.Add(new X12FunctionalGroup(groupHeader, sets));
            index++;
        }

        if (index >= segments.Count)
        {
            throw new X12ParseException(
                X12ErrorCode.MissingSegment,
                "The interchange was opened by ISA but never closed by IEA.",
                segments.Count,
                segments[segments.Count - 1].Id);
        }

        X12Segment iea = segments[index];
        int declaredGroups = ReadCount(
            iea.Value(1), index + 1, "IEA", "IEA01", X12ErrorCode.FunctionalGroupCountMismatch);
        if (declaredGroups != groups.Count)
        {
            throw new X12ParseException(
                X12ErrorCode.FunctionalGroupCountMismatch,
                $"IEA01 declares {declaredGroups.ToString(CultureInfo.InvariantCulture)} functional group(s); the interchange contains {groups.Count.ToString(CultureInfo.InvariantCulture)}.",
                index + 1,
                "IEA");
        }

        if (!NumericControlNumbersMatch(iea.Value(2), header.ControlNumber))
        {
            throw new X12ParseException(
                X12ErrorCode.ControlNumberMismatch,
                $"IEA02 is '{iea.Value(2)}' but ISA13 is '{header.ControlNumber}'; an interchange must be closed with the control number it was opened with.",
                index + 1,
                "IEA");
        }

        if (index != segments.Count - 1)
        {
            X12Segment trailing = segments[index + 1];
            throw new X12ParseException(
                X12ErrorCode.UnexpectedSegment,
                $"{(segments.Count - index - 1).ToString(CultureInfo.InvariantCulture)} segment(s) follow IEA, starting with '{trailing.Id}'. Two interchanges concatenated into one file is the usual cause; they must be read one at a time.",
                index + 2,
                trailing.Id);
        }

        return new X12Interchange(header, groups) { Delimiters = delimiters };
    }

    /// <summary>Reads one ST..SE transaction set, returning the index just past its SE.</summary>
    private static int ReadTransactionSet(
        IReadOnlyList<X12Segment> segments,
        int index,
        X12GroupHeader groupHeader,
        List<X12TransactionSet> sets)
    {
        X12Segment st = segments[index];
        if (!IsId(st, "ST"))
        {
            throw new X12ParseException(
                X12ErrorCode.UnexpectedSegment,
                $"Expected ST or GE inside functional group {groupHeader.ControlNumber}, found '{st.Id}'.",
                index + 1,
                st.Id);
        }

        int stPosition = index + 1;
        index++;

        var bodySegments = new List<X12Segment>();
        while (index < segments.Count && !IsId(segments[index], "SE"))
        {
            X12Segment current = segments[index];
            if (IsId(current, "ST") || IsId(current, "GS") || IsId(current, "GE") || IsId(current, "IEA"))
            {
                throw new X12ParseException(
                    X12ErrorCode.UnbalancedEnvelope,
                    $"Transaction set {st.Value(2)} was opened by ST but '{current.Id}' appeared before its SE.",
                    index + 1,
                    current.Id);
            }

            bodySegments.Add(current);
            index++;
        }

        if (index >= segments.Count)
        {
            throw new X12ParseException(
                X12ErrorCode.MissingSegment,
                $"Transaction set {st.Value(2)} was opened by ST but never closed by SE.",
                stPosition,
                "ST");
        }

        X12Segment se = segments[index];

        // ST02 and SE02 are AN fields and the standard requires them to be identical, so this one
        // is an ordinal comparison — "0001" and "1" are genuinely different transaction sets.
        if (!string.Equals(se.Value(2), st.Value(2), StringComparison.Ordinal))
        {
            throw new X12ParseException(
                X12ErrorCode.ControlNumberMismatch,
                $"SE02 is '{se.Value(2)}' but ST02 is '{st.Value(2)}'; a transaction set must be closed with the control number it was opened with.",
                index + 1,
                "SE");
        }

        int declaredSegments = ReadCount(
            se.Value(1), index + 1, "SE", "SE01", X12ErrorCode.SegmentCountMismatch);
        int actualSegments = bodySegments.Count + 2;
        if (declaredSegments != actualSegments)
        {
            throw new X12ParseException(
                X12ErrorCode.SegmentCountMismatch,
                $"SE01 declares {declaredSegments.ToString(CultureInfo.InvariantCulture)} segments; the transaction set contains {actualSegments.ToString(CultureInfo.InvariantCulture)} counting ST and SE themselves.",
                index + 1,
                "SE");
        }

        string? implementationReference = st.ElementCount >= 3 && st.Value(3).Length > 0 ? st.Value(3) : null;
        sets.Add(new X12TransactionSet(st.Value(1), st.Value(2), implementationReference, bodySegments));

        return index + 1;
    }

    /// <summary>
    /// Compares two N0 control numbers numerically.
    /// </summary>
    /// <remarks>
    /// ISA13 is fixed width and zero filled to nine characters; IEA02 is variable length and is
    /// commonly sent unpadded. GS06 and GE02 have the same asymmetry in practice. Comparing them
    /// ordinally rejects <c>ISA13=000000001 / IEA02=1</c>, which is a perfectly valid interchange —
    /// a false rejection that is much more expensive than the mismatch it is trying to catch.
    /// </remarks>
    private static bool NumericControlNumbersMatch(string left, string right)
    {
        string a = left.Trim();
        string b = right.Trim();

        if (long.TryParse(a, NumberStyles.None, CultureInfo.InvariantCulture, out long x)
            && long.TryParse(b, NumberStyles.None, CultureInfo.InvariantCulture, out long y))
        {
            return x == y;
        }

        return string.Equals(a, b, StringComparison.Ordinal);
    }

    private static int ReadCount(
        string value,
        int segmentPosition,
        string segmentId,
        string elementName,
        X12ErrorCode code)
    {
        if (!int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out int count))
        {
            throw new X12ParseException(
                code,
                $"{elementName} is '{value}', which is not a count.",
                segmentPosition,
                segmentId);
        }

        return count;
    }

    private static bool IsId(X12Segment segment, string id) =>
        string.Equals(segment.Id, id, StringComparison.Ordinal);

    private static string TrimLeading(string text)
    {
        int start = 0;
        // U+FEFF is a UTF-8 byte order mark decoded as a character: files exported from Windows
        // tooling carry one, and it would otherwise make the interchange fail the "starts with
        // ISA" check for a reason no one can see in a text editor.
        while (start < text.Length && (char.IsWhiteSpace(text[start]) || text[start] == '\uFEFF'))
        {
            start++;
        }

        return start == 0 ? text : text.Substring(start);
    }

    private static string Preview(string text, int length) =>
        text.Length <= length ? text : text.Substring(0, length) + "...";
}
