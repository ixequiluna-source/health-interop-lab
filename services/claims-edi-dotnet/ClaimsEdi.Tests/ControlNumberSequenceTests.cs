using System;
using System.IO;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class ControlNumberSequenceTests : IDisposable
{
    private readonly string _directory;

    public ControlNumberSequenceTests()
    {
        _directory = Path.Combine(Path.GetTempPath(), "claims-edi-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    private string CounterPath => Path.Combine(_directory, "control-number");

    [Fact]
    public void TheInMemorySequenceIncrementsFromOne()
    {
        var sequence = new InMemoryControlNumberSequence();

        Assert.Equal(1L, sequence.Next());
        Assert.Equal(2L, sequence.Next());
    }

    [Fact]
    public void TheFileSequenceSurvivesARestart()
    {
        // The failure this prevents: a container with an ephemeral filesystem restarts, the counter
        // begins at 1 again, and the trading partner rejects every interchange as a duplicate of
        // one it already has. The service looks healthy and nothing is paid.
        var first = new FileControlNumberSequence(CounterPath);
        Assert.Equal(1L, first.Next());
        Assert.Equal(2L, first.Next());

        var afterRestart = new FileControlNumberSequence(CounterPath);
        Assert.Equal(3L, afterRestart.Next());
    }

    [Fact]
    public void TheFileSequenceCreatesItsDirectory()
    {
        string nested = Path.Combine(_directory, "state", "control-number");
        var sequence = new FileControlNumberSequence(nested);

        Assert.Equal(1L, sequence.Next());
        Assert.True(File.Exists(nested));
    }

    [Fact]
    public void ACorruptCounterFileIsFatalRatherThanSilentlyReset()
    {
        File.WriteAllText(CounterPath, "not-a-number");
        var sequence = new FileControlNumberSequence(CounterPath);

        InvalidOperationException error = Assert.Throws<InvalidOperationException>(() => sequence.Next());

        Assert.Contains("duplicate interchange control numbers", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ControlNumbersDeriveFromOneSequenceValue()
    {
        X12ControlNumbers controls = X12ControlNumbers.From(42);

        // ISA13 is nine digits and zero filled; GS06 is not fixed width. Both trace back to the
        // same value so a partner's 999 rejection, which quotes GS06, identifies the interchange.
        Assert.Equal("000000042", controls.Interchange);
        Assert.Equal("42", controls.Group);
        Assert.Equal("0001", controls.TransactionSet);
        Assert.Equal("B000000042", controls.BatchReference);
    }

    [Theory]
    [InlineData(0L)]
    [InlineData(-1L)]
    [InlineData(1000000000L)]
    public void ControlNumbersOutsideTheIsa13RangeAreRejected(long sequence)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => X12ControlNumbers.From(sequence));
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
