using System;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// One encoded interchange, ready to hand to the queue.
/// </summary>
/// <param name="ClaimId">CLM01. The identity a deduplication id is derived from.</param>
/// <param name="GroupKey">
/// The FIFO ordering scope — the patient's medical record number. See
/// <see cref="ClaimRequest.GroupKey"/>.
/// </param>
/// <param name="InterchangeControlNumber">ISA13, carried as a message attribute for correlation.</param>
/// <param name="EdiPayload">The interchange itself.</param>
public sealed record ClaimSubmission(
    string ClaimId,
    string GroupKey,
    string InterchangeControlNumber,
    string EdiPayload);

/// <summary>What the queue said about a published claim.</summary>
public sealed record ClaimPublishReceipt(
    string MessageId,
    string MessageGroupId,
    string MessageDeduplicationId);

/// <summary>
/// Publishes encoded claims.
/// </summary>
/// <remarks>
/// An interface rather than a concrete SQS client so the pipeline can be exercised end to end —
/// canonical event in, validated interchange out — without AWS credentials, a network, or a
/// LocalStack container in CI.
/// </remarks>
public interface ISqsPublisher
{
    Task<ClaimPublishReceipt> PublishAsync(ClaimSubmission submission, CancellationToken cancellationToken = default);
}

/// <summary>
/// Derives the FIFO group and deduplication identifiers.
/// </summary>
/// <remarks>
/// <para>
/// <strong>Double billing is the failure mode that matters here.</strong> Every other error in this
/// service produces a rejection: a bad SE01 bounces at the clearinghouse, a bad diagnosis qualifier
/// bounces at the payer, and somebody fixes it. A duplicate 837P does not bounce. It adjudicates.
/// The payer pays the same claim twice, the duplicate surfaces months later in an overpayment
/// recovery, and — for a government payer — a pattern of duplicates is a False Claims Act exposure
/// rather than an accounting one. The asymmetry is total: losing a claim costs a resubmission,
/// duplicating one costs a compliance incident.
/// </para>
/// <para>
/// So the deduplication id is derived <em>only</em> from the claim id. Not from the payload, not
/// from a timestamp, not from a GUID. Anything that varies between attempts — a rebuilt
/// interchange with a new ISA13, a retry after a socket timeout where the first send actually
/// succeeded — would produce a different id and defeat the mechanism precisely when it is needed.
/// </para>
/// <para>
/// <strong>The five-minute window is not idempotency.</strong> SQS FIFO deduplicates within a
/// five-minute interval. That covers the case this is designed for: an ambiguous send, a retry
/// storm, a pod restarting mid-batch. It does <em>not</em> cover resubmitting the same claim
/// tomorrow, which needs a durable submitted-claims store keyed on the same claim id. This is one
/// layer, and the comment exists so nobody mistakes it for the whole defence.
/// </para>
/// </remarks>
public static class ClaimDeduplication
{
    /// <summary>SQS caps both identifiers at 128 characters.</summary>
    public const int MaxIdentifierLength = 128;

    private const int MaxGroupPrefixLength = 100;
    private const int GroupHashLength = 8;

    /// <summary>
    /// A stable FIFO message group id for an ordering key.
    /// </summary>
    /// <remarks>
    /// The readable prefix keeps the group id greppable in the console; the hash suffix keeps two
    /// keys that sanitise to the same prefix in different groups. Collapsing them would silently
    /// serialise two unrelated patients' claims behind one another — harmless for correctness,
    /// but a throughput cliff that is very hard to diagnose.
    /// </remarks>
    public static string GroupIdFor(string groupKey)
    {
        if (string.IsNullOrWhiteSpace(groupKey))
        {
            throw new ArgumentException("A FIFO message group id cannot be derived from an empty key.", nameof(groupKey));
        }

        string hash = Sha256Hex(groupKey);
        var prefix = new StringBuilder(MaxGroupPrefixLength);

        foreach (char c in groupKey)
        {
            if (prefix.Length == MaxGroupPrefixLength)
            {
                break;
            }

            bool safe = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';

            prefix.Append(safe ? c : '_');
        }

        return prefix.Append('-').Append(hash, 0, GroupHashLength).ToString();
    }

    /// <summary>
    /// The deterministic deduplication id for a claim: SHA-256 of the claim id, hex encoded.
    /// </summary>
    /// <remarks>
    /// Hashing rather than passing the claim id through does two things. It bounds the length
    /// (claim ids come from hospital account numbers and are not under our control), and it keeps
    /// the identifier free of characters SQS rejects without a sanitising step that could map two
    /// distinct claims onto the same id — which, here, would mean silently dropping a real claim.
    /// </remarks>
    public static string DeduplicationIdFor(string claimId)
    {
        if (string.IsNullOrWhiteSpace(claimId))
        {
            throw new ArgumentException("A deduplication id cannot be derived from an empty claim id.", nameof(claimId));
        }

        // The prefix namespaces the digest, so a future "void this claim" message derived from the
        // same claim id cannot collide with the original submission.
        return Sha256Hex("claim-837p:" + claimId);
    }

    private static string Sha256Hex(string value)
    {
        byte[] digest = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(digest).ToLowerInvariant();
    }
}

/// <summary>
/// A publisher that writes to the console instead of to SQS.
/// </summary>
/// <remarks>
/// The equivalent of the upstream service's in-memory Kafka sink: it makes the whole pipeline
/// runnable with no cloud account, which is what makes the failure modes in the README
/// reproducible by anyone reading them.
/// </remarks>
public sealed class DryRunPublisher : ISqsPublisher
{
    private readonly Action<string> _write;
    private long _counter;

    public DryRunPublisher(Action<string>? write = null)
    {
        _write = write ?? Console.Out.WriteLine;
    }

    public Task<ClaimPublishReceipt> PublishAsync(
        ClaimSubmission submission,
        CancellationToken cancellationToken = default)
    {
        if (submission is null)
        {
            throw new ArgumentNullException(nameof(submission));
        }

        cancellationToken.ThrowIfCancellationRequested();

        string groupId = ClaimDeduplication.GroupIdFor(submission.GroupKey);
        string dedupId = ClaimDeduplication.DeduplicationIdFor(submission.ClaimId);
        long sequence = Interlocked.Increment(ref _counter);

        _write(submission.EdiPayload);

        return Task.FromResult(new ClaimPublishReceipt(
            "dry-run-" + sequence.ToString(CultureInfo.InvariantCulture),
            groupId,
            dedupId));
    }
}
