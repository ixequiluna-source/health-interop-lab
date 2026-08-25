using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Amazon;
using Amazon.SQS;
using Amazon.SQS.Model;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// Publishes encoded claims onto an SQS FIFO queue.
/// </summary>
/// <remarks>
/// <para>
/// The queue must be FIFO (a <c>.fifo</c> suffix). Both properties this service depends on —
/// ordering within a message group and content deduplication — exist only on FIFO queues. Pointing
/// this at a standard queue does not fail; it succeeds, silently discards
/// <c>MessageGroupId</c> and <c>MessageDeduplicationId</c>, and reintroduces exactly the
/// double-billing risk they were added to remove. Hence the check in the constructor.
/// </para>
/// <para>
/// Retries are left to the AWS SDK, which retries on the errors worth retrying with the right
/// backoff. That is only safe because the deduplication id is derived from the claim id rather than
/// from the attempt: a retried send of a claim SQS has already accepted is deduplicated away
/// instead of becoming a second claim.
/// </para>
/// </remarks>
public sealed class SqsPublisher : ISqsPublisher, IDisposable
{
    private const string FifoQueueSuffix = ".fifo";

    private readonly IAmazonSQS _sqs;
    private readonly string _queueUrl;
    private readonly bool _ownsClient;

    /// <summary>
    /// Wraps an SQS client. The client is injected so tests can supply their own; ownership is
    /// explicit so this type never disposes a client its caller is still using.
    /// </summary>
    public SqsPublisher(IAmazonSQS sqs, string queueUrl, bool ownsClient = false)
    {
        _sqs = sqs ?? throw new ArgumentNullException(nameof(sqs));

        if (string.IsNullOrWhiteSpace(queueUrl))
        {
            throw new ArgumentException("A queue URL is required.", nameof(queueUrl));
        }

        if (!queueUrl.EndsWith(FifoQueueSuffix, StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException(
                $"'{queueUrl}' is not a FIFO queue. A standard queue accepts a send with a MessageDeduplicationId and ignores it, so a retried submission becomes a duplicate claim rather than a no-op.",
                nameof(queueUrl));
        }

        _queueUrl = queueUrl;
        _ownsClient = ownsClient;
    }

    /// <summary>Builds a publisher with a client created from the ambient AWS configuration.</summary>
    public static SqsPublisher ForQueue(string queueUrl, string? regionSystemName = null)
    {
        var config = new AmazonSQSConfig();
        if (!string.IsNullOrWhiteSpace(regionSystemName))
        {
            config.RegionEndpoint = RegionEndpoint.GetBySystemName(regionSystemName);
        }

        // Credentials come from the default provider chain, which on EKS means the pod's IAM role.
        // Nothing in this service reads an access key from configuration.
        return new SqsPublisher(new AmazonSQSClient(config), queueUrl, ownsClient: true);
    }

    public async Task<ClaimPublishReceipt> PublishAsync(
        ClaimSubmission submission,
        CancellationToken cancellationToken = default)
    {
        if (submission is null)
        {
            throw new ArgumentNullException(nameof(submission));
        }

        string groupId = ClaimDeduplication.GroupIdFor(submission.GroupKey);
        string deduplicationId = ClaimDeduplication.DeduplicationIdFor(submission.ClaimId);

        var request = new SendMessageRequest
        {
            QueueUrl = _queueUrl,
            MessageBody = submission.EdiPayload,

            // Ordering scope. Claims for one patient stay in order relative to each other, which is
            // what makes a replacement claim (CLM05-3 = 7) meaningful; claims for different
            // patients are free to be processed in parallel.
            MessageGroupId = groupId,

            // Deterministic in the claim id, so a retry cannot double-bill. See ClaimDeduplication.
            MessageDeduplicationId = deduplicationId,

            MessageAttributes = new Dictionary<string, MessageAttributeValue>(StringComparer.Ordinal)
            {
                // Attributes carry correlation identifiers only. The body is an 837P and is
                // therefore PHI; attributes end up in queue metrics, dead-letter dumps and support
                // tickets, which are not in the same access regime as the claim store.
                ["claimId"] = new MessageAttributeValue { DataType = "String", StringValue = submission.ClaimId },
                ["interchangeControlNumber"] = new MessageAttributeValue
                {
                    DataType = "String",
                    StringValue = submission.InterchangeControlNumber,
                },
                ["transactionSet"] = new MessageAttributeValue { DataType = "String", StringValue = "837P" },
            },
        };

        SendMessageResponse response = await _sqs.SendMessageAsync(request, cancellationToken)
            .ConfigureAwait(false);

        return new ClaimPublishReceipt(response.MessageId, groupId, deduplicationId);
    }

    public void Dispose()
    {
        if (_ownsClient && _sqs is IDisposable disposable)
        {
            disposable.Dispose();
        }
    }
}
