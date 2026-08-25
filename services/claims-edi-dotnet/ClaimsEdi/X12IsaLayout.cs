using System;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// The fixed-width layout of the ISA interchange control header.
/// </summary>
/// <remarks>
/// <para>
/// ISA is the only segment in X12 whose elements have fixed widths. Every other segment is
/// variable-length and delimiter-delimited; ISA is both. It must be exactly 106 characters
/// including its terminator, and a reader is entitled to — and every commercial translator does —
/// locate the delimiters by byte offset before it has parsed anything.
/// </para>
/// <para>
/// This is the classic X12 failure. A writer that emits <c>ISA*00*  *00*  *ZZ*FIRMUS*ZZ*PAYER*...</c>
/// with a 6-character sender id instead of a 15-character space-padded one produces an ISA of 97
/// characters. The partner's translator then reads offset 105 for the segment terminator, finds a
/// digit of the control number, and rejects the whole interchange with a TA1 error that says
/// nothing more useful than "invalid interchange header". Nothing downstream is wrong; only the
/// padding is. Hence the widths live here as a table and both the writer and the reader use them.
/// </para>
/// </remarks>
public static class X12IsaLayout
{
    /// <summary>Total length of the ISA segment including the segment terminator.</summary>
    public const int SegmentLength = 106;

    /// <summary>Number of elements in ISA (ISA01 through ISA16).</summary>
    public const int ElementCount = 16;

    /// <summary>Offset of the element separator. It is the character immediately after "ISA".</summary>
    public const int ElementSeparatorIndex = 3;

    /// <summary>Offset of ISA11, the repetition separator.</summary>
    public const int RepetitionSeparatorIndex = 82;

    /// <summary>Offset of ISA16, the component separator.</summary>
    public const int ComponentSeparatorIndex = 104;

    /// <summary>Offset of the segment terminator.</summary>
    public const int SegmentTerminatorIndex = 105;

    /// <summary>
    /// ISA11. Its value is the repetition separator itself, so it is exempt from the
    /// "no delimiters in data" rule that applies to every other element.
    /// </summary>
    public const int RepetitionSeparatorElement = 11;

    /// <summary>
    /// ISA16. Its value is the component separator itself, and it is exempt for the same reason.
    /// </summary>
    public const int ComponentSeparatorElement = 16;

    // Index 0 is unused so that the array is addressed by the element's X12 number (ISA01 -> 1).
    // 3 ("ISA") + 1 (separator after the id) + sum(widths) + 16 (one separator per element,
    // the last of which is the segment terminator) = 3 + 1 + 86 + 16 = 106.
    private static readonly int[] Widths =
    {
        0,  // unused
        2,  // ISA01 authorization information qualifier      ID
        10, // ISA02 authorization information                AN
        2,  // ISA03 security information qualifier           ID
        10, // ISA04 security information                     AN
        2,  // ISA05 interchange sender id qualifier          ID
        15, // ISA06 interchange sender id                    AN
        2,  // ISA07 interchange receiver id qualifier        ID
        15, // ISA08 interchange receiver id                  AN
        6,  // ISA09 interchange date       YYMMDD            DT
        4,  // ISA10 interchange time       HHMM              TM
        1,  // ISA11 repetition separator                     (encoding, not data)
        5,  // ISA12 interchange control version number       ID
        9,  // ISA13 interchange control number               N0
        1,  // ISA14 acknowledgment requested                 ID
        1,  // ISA15 usage indicator (T test / P production)  ID
        1,  // ISA16 component element separator              (encoding, not data)
    };

    /// <summary>How a given ISA element is padded to its fixed width.</summary>
    public enum Padding
    {
        /// <summary>AN fields: left justified, space filled on the right.</summary>
        SpaceRight,

        /// <summary>N0 fields: right justified, zero filled on the left. ISA13 only.</summary>
        ZeroLeft,

        /// <summary>ID, DT and TM fields whose length is fixed by the standard, not by padding.</summary>
        Exact,
    }

    /// <summary>Width of an ISA element, addressed by its X12 number (1..16).</summary>
    public static int ElementWidth(int elementNumber)
    {
        if (elementNumber < 1 || elementNumber > ElementCount)
        {
            throw new ArgumentOutOfRangeException(
                nameof(elementNumber),
                elementNumber,
                "ISA has elements 1 through 16.");
        }

        return Widths[elementNumber];
    }

    /// <summary>Padding rule for an ISA element, addressed by its X12 number (1..16).</summary>
    public static Padding ElementPadding(int elementNumber) => elementNumber switch
    {
        // The four AN fields. An empty authorization/security field is ten spaces, not nothing —
        // that is what makes ISA02 and ISA04 disappear visually while still occupying their width.
        2 or 4 or 6 or 8 => Padding.SpaceRight,

        // ISA13 is N0. Senders that emit "1" instead of "000000001" produce a 98-character ISA.
        13 => Padding.ZeroLeft,

        _ => Padding.Exact,
    };
}
