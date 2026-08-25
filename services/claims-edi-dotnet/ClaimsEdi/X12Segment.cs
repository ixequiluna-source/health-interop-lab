using System;
using System.Collections.Generic;
using System.Text;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// One X12 element: either a simple value or a composite made of components.
/// </summary>
/// <remarks>
/// <para>
/// Modelling an element as a list of components rather than as a raw string is what lets the
/// writer distinguish "this element legitimately contains the component separator because it is
/// CLM05" from "this element contains the component separator because a patient's name has a colon
/// in it". Store elements as strings and you must either allow every delimiter through (corrupting
/// the interchange) or reject composites (making 837P unwritable).
/// </para>
/// <para>
/// Trailing empty components are dropped at construction. X12 requires trailing empty components
/// and elements to be suppressed, so <c>11:B:</c> and <c>11:B</c> are the same element; normalising
/// on the way in is what makes write-then-read round-trip exactly rather than approximately.
/// </para>
/// </remarks>
public sealed class X12Element : IEquatable<X12Element>
{
    private static readonly string[] EmptyComponents = { string.Empty };

    private readonly string[] _components;

    private X12Element(string[] components)
    {
        _components = components;
    }

    /// <summary>The empty element.</summary>
    public static X12Element Empty { get; } = new(EmptyComponents);

    /// <summary>An element with a single component.</summary>
    public static X12Element Simple(string? value) =>
        string.IsNullOrEmpty(value) ? Empty : new X12Element(new[] { value });

    /// <summary>
    /// A composite element. Trailing empty components are dropped; interior ones are preserved,
    /// because <c>ABK::x</c> and <c>ABK:x</c> point at different component positions.
    /// </summary>
    public static X12Element Composite(params string[] components)
    {
        if (components is null || components.Length == 0)
        {
            return Empty;
        }

        int last = -1;
        for (int i = components.Length - 1; i >= 0; i--)
        {
            if (!string.IsNullOrEmpty(components[i]))
            {
                last = i;
                break;
            }
        }

        if (last < 0)
        {
            return Empty;
        }

        var trimmed = new string[last + 1];
        for (int i = 0; i <= last; i++)
        {
            trimmed[i] = components[i] ?? string.Empty;
        }

        return new X12Element(trimmed);
    }

    /// <summary>Components, in order. Always at least one entry.</summary>
    public IReadOnlyList<string> Components => _components;

    /// <summary>Number of components; 1 for a simple element.</summary>
    public int ComponentCount => _components.Length;

    /// <summary>The first component — the whole value for a simple element.</summary>
    public string Value => _components[0];

    /// <summary>True when this element carries more than one component.</summary>
    public bool IsComposite => _components.Length > 1;

    /// <summary>True when the element carries no data at all.</summary>
    public bool IsEmpty => _components.Length == 1 && _components[0].Length == 0;

    /// <summary>
    /// Component by its 1-based X12 position. Positions past the end read as empty rather than
    /// throwing: a suppressed trailing component is "not sent", which is a normal state, and
    /// callers should not have to bounds-check every optional sub-element.
    /// </summary>
    public string Component(int componentNumber) =>
        componentNumber >= 1 && componentNumber <= _components.Length
            ? _components[componentNumber - 1]
            : string.Empty;

    /// <summary>Implicit lift from a string so segment construction stays readable.</summary>
    public static implicit operator X12Element(string? value) => Simple(value);

    /// <summary>Explicit equivalent of the implicit conversion, for callers that dislike operators.</summary>
    public static X12Element FromString(string? value) => Simple(value);

    public bool Equals(X12Element? other)
    {
        if (other is null)
        {
            return false;
        }

        if (ReferenceEquals(this, other))
        {
            return true;
        }

        if (_components.Length != other._components.Length)
        {
            return false;
        }

        for (int i = 0; i < _components.Length; i++)
        {
            if (!string.Equals(_components[i], other._components[i], StringComparison.Ordinal))
            {
                return false;
            }
        }

        return true;
    }

    public override bool Equals(object? obj) => Equals(obj as X12Element);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        foreach (string component in _components)
        {
            hash.Add(component, StringComparer.Ordinal);
        }

        return hash.ToHashCode();
    }

    /// <summary>Diagnostics only — uses a neutral separator so it can never be mistaken for EDI.</summary>
    public override string ToString() => string.Join("|", _components);
}

/// <summary>
/// One X12 segment: an identifier plus its elements.
/// </summary>
/// <remarks>
/// Trailing empty elements are dropped at construction, for the same reason trailing components
/// are: X12 requires them to be suppressed on the wire, so a segment that "has" them and a segment
/// that does not are the same segment. Normalising here means <see cref="Equals(X12Segment)"/> is a
/// usable definition of round-trip equivalence.
/// </remarks>
public sealed class X12Segment : IEquatable<X12Segment>
{
    private static readonly X12Element[] NoElements = Array.Empty<X12Element>();

    private readonly X12Element[] _elements;

    public X12Segment(string id, params X12Element[] elements)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            throw new ArgumentException("A segment identifier must not be empty.", nameof(id));
        }

        Id = id;
        _elements = TrimTrailingEmpty(elements);
    }

    /// <summary>The segment identifier, for example <c>NM1</c> or <c>CLM</c>.</summary>
    public string Id { get; }

    /// <summary>Elements, in order, with trailing empties suppressed.</summary>
    public IReadOnlyList<X12Element> Elements => _elements;

    /// <summary>Number of elements actually carried.</summary>
    public int ElementCount => _elements.Length;

    /// <summary>Element by its 1-based X12 position; past the end reads as empty.</summary>
    public X12Element Element(int elementNumber) =>
        elementNumber >= 1 && elementNumber <= _elements.Length
            ? _elements[elementNumber - 1]
            : X12Element.Empty;

    /// <summary>The simple value of an element by its 1-based X12 position.</summary>
    public string Value(int elementNumber) => Element(elementNumber).Value;

    /// <summary>A component of a composite element, both positions 1-based.</summary>
    public string Component(int elementNumber, int componentNumber) =>
        Element(elementNumber).Component(componentNumber);

    public bool Equals(X12Segment? other)
    {
        if (other is null)
        {
            return false;
        }

        if (ReferenceEquals(this, other))
        {
            return true;
        }

        if (!string.Equals(Id, other.Id, StringComparison.Ordinal) || _elements.Length != other._elements.Length)
        {
            return false;
        }

        for (int i = 0; i < _elements.Length; i++)
        {
            if (!_elements[i].Equals(other._elements[i]))
            {
                return false;
            }
        }

        return true;
    }

    public override bool Equals(object? obj) => Equals(obj as X12Segment);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        hash.Add(Id, StringComparer.Ordinal);
        foreach (X12Element element in _elements)
        {
            hash.Add(element);
        }

        return hash.ToHashCode();
    }

    /// <summary>
    /// Diagnostics only. Rendering for the wire needs the interchange's delimiters and therefore
    /// lives on <see cref="X12Writer"/>; a <c>ToString</c> that guessed <c>*</c> would be the exact
    /// hard-coded-delimiter bug this codebase exists to avoid.
    /// </summary>
    public override string ToString()
    {
        var builder = new StringBuilder(Id);
        foreach (X12Element element in _elements)
        {
            builder.Append('|').Append(element.ToString());
        }

        return builder.ToString();
    }

    private static X12Element[] TrimTrailingEmpty(X12Element[] elements)
    {
        if (elements is null || elements.Length == 0)
        {
            return NoElements;
        }

        int last = -1;
        for (int i = elements.Length - 1; i >= 0; i--)
        {
            X12Element candidate = elements[i] ?? X12Element.Empty;
            if (!candidate.IsEmpty)
            {
                last = i;
                break;
            }
        }

        if (last < 0)
        {
            return NoElements;
        }

        var trimmed = new X12Element[last + 1];
        for (int i = 0; i <= last; i++)
        {
            trimmed[i] = elements[i] ?? X12Element.Empty;
        }

        return trimmed;
    }
}
