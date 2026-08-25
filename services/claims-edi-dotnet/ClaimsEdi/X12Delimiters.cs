using System;
using System.Text;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// The four characters that encode an X12 interchange.
/// </summary>
/// <remarks>
/// <para>
/// These are <em>data</em>, not constants. Every interchange declares its own encoding inside its
/// own ISA segment: the element separator sits at ISA offset 3, the repetition separator at 82,
/// the component separator at 104 and the segment terminator at 105. A reader that hard-codes
/// <c>*</c> and <c>~</c> works against every sample file on the internet and then fails on the
/// first real trading partner that uses <c>|</c> and a newline — and it fails silently, because
/// splitting on a character the sender never used yields one enormous "segment" that is then
/// mis-mapped rather than rejected.
/// </para>
/// <para>
/// X12 has no escape mechanism. Unlike HL7 v2, which can carry a literal field separator as
/// <c>\F\</c>, there is no way to represent a delimiter inside an element value. That is why this
/// type also owns the "is this character forbidden in data" question — see
/// <see cref="IndexOfForbidden"/> — and why <see cref="X12Writer"/> refuses (or strips) rather
/// than emitting a value that would silently shift every later element in the segment.
/// </para>
/// </remarks>
public sealed class X12Delimiters : IEquatable<X12Delimiters>
{
    /// <summary>Element separator used by the overwhelming majority of US healthcare partners.</summary>
    public const char DefaultElement = '*';

    /// <summary>Component (sub-element) separator, ISA16.</summary>
    public const char DefaultComponent = ':';

    /// <summary>Repetition separator, ISA11. Introduced as a real separator in version 00501.</summary>
    public const char DefaultRepetition = '^';

    /// <summary>Segment terminator.</summary>
    public const char DefaultSegment = '~';

    /// <summary>The conventional 005010 delimiter set: <c>* : ^ ~</c>.</summary>
    public static X12Delimiters Default { get; } =
        new(DefaultElement, DefaultComponent, DefaultRepetition, DefaultSegment);

    /// <summary>
    /// Creates a delimiter set, rejecting combinations that cannot round-trip.
    /// </summary>
    /// <exception cref="ArgumentException">
    /// A delimiter is a letter or digit (segment identifiers would be indistinguishable from
    /// separators), is NUL, or two delimiters are the same character (the encoding would be
    /// ambiguous: an element boundary would also read as a segment boundary).
    /// </exception>
    public X12Delimiters(char element, char component, char repetition, char segment)
    {
        Validate(element, nameof(element), allowWhitespace: false);
        Validate(component, nameof(component), allowWhitespace: false);
        Validate(repetition, nameof(repetition), allowWhitespace: false);

        // The segment terminator is the one delimiter that partners legitimately set to a control
        // character: '\n' terminated interchanges are common from mainframe senders.
        Validate(segment, nameof(segment), allowWhitespace: true);

        RequireDistinct(element, component, nameof(element), nameof(component));
        RequireDistinct(element, repetition, nameof(element), nameof(repetition));
        RequireDistinct(element, segment, nameof(element), nameof(segment));
        RequireDistinct(component, repetition, nameof(component), nameof(repetition));
        RequireDistinct(component, segment, nameof(component), nameof(segment));
        RequireDistinct(repetition, segment, nameof(repetition), nameof(segment));

        Element = element;
        Component = component;
        Repetition = repetition;
        Segment = segment;
    }

    /// <summary>Separates elements within a segment. ISA offset 3.</summary>
    public char Element { get; }

    /// <summary>Separates components within a composite element. ISA16, offset 104.</summary>
    public char Component { get; }

    /// <summary>Separates repetitions of a repeating element. ISA11, offset 82.</summary>
    public char Repetition { get; }

    /// <summary>Terminates a segment. ISA offset 105.</summary>
    public char Segment { get; }

    /// <summary>True when <paramref name="candidate"/> is one of the four delimiters.</summary>
    public bool IsDelimiter(char candidate) =>
        candidate == Element || candidate == Component || candidate == Repetition || candidate == Segment;

    /// <summary>
    /// Index of the first character that cannot appear in element data, or -1.
    /// </summary>
    /// <remarks>
    /// CR and LF are forbidden alongside the four delimiters. Readers routinely trim line endings
    /// around segment terminators so that "one segment per line" files parse, which means a CR or
    /// LF embedded in a value is not merely ugly — it is indistinguishable from formatting and will
    /// be eaten by the next reader in the chain.
    /// </remarks>
    public int IndexOfForbidden(string value)
    {
        if (value is null)
        {
            return -1;
        }

        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            if (IsDelimiter(c) || c == '\r' || c == '\n')
            {
                return i;
            }
        }

        return -1;
    }

    /// <summary>Returns <paramref name="value"/> with every forbidden character removed.</summary>
    public string RemoveForbidden(string value)
    {
        if (value is null || IndexOfForbidden(value) < 0)
        {
            return value ?? string.Empty;
        }

        var builder = new StringBuilder(value.Length);
        foreach (char c in value)
        {
            if (!IsDelimiter(c) && c != '\r' && c != '\n')
            {
                builder.Append(c);
            }
        }

        return builder.ToString();
    }

    public bool Equals(X12Delimiters? other) =>
        other is not null
        && Element == other.Element
        && Component == other.Component
        && Repetition == other.Repetition
        && Segment == other.Segment;

    public override bool Equals(object? obj) => Equals(obj as X12Delimiters);

    public override int GetHashCode() => HashCode.Combine(Element, Component, Repetition, Segment);

    public override string ToString() =>
        $"element='{Describe(Element)}' component='{Describe(Component)}' repetition='{Describe(Repetition)}' segment='{Describe(Segment)}'";

    /// <summary>Renders a delimiter for error messages, so a newline terminator is readable.</summary>
    public static string Describe(char value) => value switch
    {
        '\r' => "\\r",
        '\n' => "\\n",
        '\t' => "\\t",
        _ when char.IsControl(value) => "\\u" + ((int)value).ToString("x4", System.Globalization.CultureInfo.InvariantCulture),
        _ => value.ToString(),
    };

    private static void Validate(char value, string parameterName, bool allowWhitespace)
    {
        if (value == '\0')
        {
            throw new ArgumentException("A delimiter must not be NUL.", parameterName);
        }

        if (char.IsLetterOrDigit(value))
        {
            throw new ArgumentException(
                $"'{value}' is a letter or digit and cannot be a delimiter: segment identifiers and element data would be indistinguishable from separators.",
                parameterName);
        }

        if (!allowWhitespace && char.IsWhiteSpace(value))
        {
            throw new ArgumentException(
                $"'{Describe(value)}' is whitespace and cannot be used as this delimiter; only the segment terminator may be a control character.",
                parameterName);
        }
    }

    private static void RequireDistinct(char left, char right, string leftName, string rightName)
    {
        if (left == right)
        {
            throw new ArgumentException(
                $"The {leftName} and {rightName} delimiters are both '{Describe(left)}'; the encoding would be ambiguous.",
                rightName);
        }
    }
}
