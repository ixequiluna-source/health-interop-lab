using System;
using System.Globalization;
using System.IO;
using System.Threading;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// Supplies monotonically increasing interchange control numbers.
/// </summary>
/// <remarks>
/// <para>
/// This looks like an incidental detail and is not. Trading partners deduplicate on ISA13 over a
/// retention window measured in months, and a repeated control number is rejected as a duplicate
/// interchange — every claim inside it included, with an acknowledgement that says "duplicate"
/// rather than anything about the claims.
/// </para>
/// <para>
/// The failure this abstraction exists to make visible: a container with an ephemeral filesystem
/// restarts, the counter starts at 1 again, and every interchange for the next several days is
/// silently rejected as a duplicate of one already received. The service looks healthy, the queue
/// drains, and nothing is paid.
/// </para>
/// </remarks>
public interface IControlNumberSequence
{
    /// <summary>Returns the next control number. Must be safe to call from multiple threads.</summary>
    long Next();
}

/// <summary>
/// A counter that lives only as long as the process. For tests and dry runs.
/// </summary>
public sealed class InMemoryControlNumberSequence : IControlNumberSequence
{
    private long _value;

    public InMemoryControlNumberSequence(long start = 0)
    {
        if (start < 0 || start > X12ControlNumbers.MaxSequence)
        {
            throw new ArgumentOutOfRangeException(nameof(start), start, "Out of ISA13 range.");
        }

        _value = start;
    }

    public long Next()
    {
        long next = Interlocked.Increment(ref _value);
        if (next > X12ControlNumbers.MaxSequence)
        {
            throw new InvalidOperationException("The in-memory control number sequence has exhausted the ISA13 range.");
        }

        return next;
    }
}

/// <summary>
/// A counter persisted to a file, so it survives a restart.
/// </summary>
/// <remarks>
/// The file must be on a volume that outlives the container. A single writer is assumed — the
/// process-level lock protects concurrent claims within this instance, not two instances sharing
/// a volume. Two writers need a real sequence (DynamoDB conditional update, a database sequence);
/// pretending a file lock is enough for that is how partners end up with two interchanges claiming
/// the same ISA13.
/// </remarks>
public sealed class FileControlNumberSequence : IControlNumberSequence
{
    private readonly string _path;
    private readonly object _gate = new();

    public FileControlNumberSequence(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new ArgumentException("A path is required.", nameof(path));
        }

        _path = path;

        string? directory = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }
    }

    /// <summary>The path the counter is persisted to.</summary>
    public string FilePath => _path;

    public long Next()
    {
        lock (_gate)
        {
            long current = Read();
            long next = current + 1;

            if (next > X12ControlNumbers.MaxSequence)
            {
                // ISA13 is nine digits. Wrapping is correct — by the time a submitter has sent a
                // billion interchanges the partner's retention window has long since expired — but
                // it is worth being explicit that it happens rather than overflowing into an
                // ArgumentOutOfRangeException nobody expected.
                next = 1;
            }

            Write(next);
            return next;
        }
    }

    private long Read()
    {
        if (!File.Exists(_path))
        {
            return 0;
        }

        string raw = File.ReadAllText(_path).Trim();
        if (raw.Length == 0)
        {
            return 0;
        }

        if (!long.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out long value)
            || value < 0
            || value > X12ControlNumbers.MaxSequence)
        {
            // Deliberately fatal. Resetting to zero here would be the ephemeral-filesystem failure
            // by another route: quiet, plausible, and it produces months of duplicate rejections.
            throw new InvalidOperationException(
                $"The control number file '{_path}' contains '{raw}', which is not a usable ISA13 sequence value. Refusing to reset it, because starting over silently produces duplicate interchange control numbers at the trading partner.");
        }

        return value;
    }

    private void Write(long value)
    {
        // Write-then-rename: a crash between the two leaves the previous value intact rather than a
        // truncated file that Read would then refuse to parse.
        string temporary = _path + ".tmp";
        File.WriteAllText(temporary, value.ToString(CultureInfo.InvariantCulture));
        File.Move(temporary, _path, overwrite: true);
    }
}
