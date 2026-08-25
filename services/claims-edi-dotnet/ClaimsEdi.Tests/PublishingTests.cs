using System;
using System.Threading.Tasks;
using Amazon.SQS;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class ClaimDeduplicationTests
{
    [Fact]
    public void TheDeduplicationIdDependsOnlyOnTheClaimId()
    {
        // A retry rebuilds the interchange with a fresh ISA13 and a fresh submission timestamp.
        // If either of those reached the deduplication id, the retry would be published as a second
        // claim and the payer would adjudicate both.
        string first = ClaimDeduplication.DeduplicationIdFor("CLM-1001");
        string second = ClaimDeduplication.DeduplicationIdFor("CLM-1001");

        Assert.Equal(first, second);
    }

    [Fact]
    public void DifferentClaimsGetDifferentDeduplicationIds()
    {
        Assert.NotEqual(
            ClaimDeduplication.DeduplicationIdFor("CLM-1001"),
            ClaimDeduplication.DeduplicationIdFor("CLM-1002"));
    }

    [Fact]
    public void TheDeduplicationIdFitsInsideSqsLimits()
    {
        string id = ClaimDeduplication.DeduplicationIdFor("A-VERY-LONG-HOSPITAL-ACCOUNT-NUMBER-0001");

        Assert.Equal(64, id.Length); // SHA-256, hex encoded
        Assert.True(id.Length <= ClaimDeduplication.MaxIdentifierLength);

        foreach (char c in id)
        {
            Assert.True((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'), $"unexpected character '{c}'");
        }
    }

    [Fact]
    public void AnEmptyClaimIdIsRejectedRatherThanHashedToAConstant()
    {
        // Hashing "" would give every unidentified claim the same deduplication id, and SQS would
        // silently drop all but the first — losing claims instead of duplicating them, which is a
        // different failure but not a better one.
        Assert.Throws<ArgumentException>(() => ClaimDeduplication.DeduplicationIdFor("  "));
    }

    [Fact]
    public void TheGroupIdIsStableForAnOrderingKey()
    {
        Assert.Equal(
            ClaimDeduplication.GroupIdFor(TestData.Mrn),
            ClaimDeduplication.GroupIdFor(TestData.Mrn));
    }

    [Fact]
    public void TheGroupIdSanitisesUnsafeCharactersWithoutCollapsingDistinctKeys()
    {
        string first = ClaimDeduplication.GroupIdFor("MRN 1");
        string second = ClaimDeduplication.GroupIdFor("MRN/1");

        // Both sanitise to the same readable prefix; the hash suffix keeps them apart, so two
        // patients are not serialised into one FIFO ordering group.
        Assert.StartsWith("MRN_1-", first, StringComparison.Ordinal);
        Assert.StartsWith("MRN_1-", second, StringComparison.Ordinal);
        Assert.NotEqual(first, second);
    }

    [Fact]
    public void TheGroupIdFitsInsideSqsLimits()
    {
        string id = ClaimDeduplication.GroupIdFor(new string('X', 500));

        Assert.True(
            id.Length <= ClaimDeduplication.MaxIdentifierLength,
            $"group id was {id.Length} characters");
    }
}

public sealed class SqsPublisherTests
{
    /// <summary>
    /// A real client with fake credentials. Constructing one touches no network, and the guard
    /// under test fires before any call is made — which is cheaper than mocking the forty-odd
    /// members of <see cref="IAmazonSQS"/> to assert one constructor precondition.
    /// </summary>
    private static AmazonSQSClient OfflineClient() =>
        new(new Amazon.Runtime.BasicAWSCredentials("unused", "unused"), Amazon.RegionEndpoint.USEast1);

    [Fact]
    public void AStandardQueueIsRejected()
    {
        using AmazonSQSClient client = OfflineClient();

        ArgumentException error = Assert.Throws<ArgumentException>(
            () => new SqsPublisher(client, "https://sqs.us-east-1.amazonaws.com/1234/claims"));

        // A standard queue accepts MessageDeduplicationId and ignores it. Failing at construction
        // is the only place this can be caught before it becomes a duplicate claim.
        Assert.Contains("FIFO", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AFifoQueueIsAccepted()
    {
        using AmazonSQSClient client = OfflineClient();
        using var publisher = new SqsPublisher(client, "https://sqs.us-east-1.amazonaws.com/1234/claims.fifo");

        Assert.NotNull(publisher);
    }
}

public sealed class PipelineTests
{
    [Fact]
    public async Task ACanonicalEventBecomesAPublishableInterchange()
    {
        ClaimRequest claim = TestData.Claim();
        X12Interchange interchange = TestData.BuildClaimInterchange(claim);
        string edi = new X12Writer().Write(interchange);

        var publisher = new FakeSqsPublisher();
        await publisher.PublishAsync(
            new ClaimSubmission(claim.ClaimId, claim.GroupKey, interchange.Header.ControlNumber, edi));

        ClaimSubmission submission = Assert.Single(publisher.Submissions);
        Assert.Equal(TestData.ClaimId, submission.ClaimId);
        Assert.Equal(TestData.Mrn, submission.GroupKey);
        Assert.Equal("000000001", submission.InterchangeControlNumber);
        Assert.StartsWith("ISA*", submission.EdiPayload, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ARetryWithANewInterchangeControlNumberKeepsTheSameDeduplicationId()
    {
        ClaimRequest claim = TestData.Claim();

        string first = new X12Writer().Write(TestData.BuildClaimInterchange(claim, sequence: 1));
        string second = new X12Writer().Write(TestData.BuildClaimInterchange(claim, sequence: 2));

        Assert.NotEqual(first, second); // different ISA13, so the payloads genuinely differ

        var publisher = new FakeSqsPublisher();
        await publisher.PublishAsync(new ClaimSubmission(claim.ClaimId, claim.GroupKey, "000000001", first));
        await publisher.PublishAsync(new ClaimSubmission(claim.ClaimId, claim.GroupKey, "000000002", second));

        // SQS would deduplicate these within its five-minute window: the claim is billed once.
        Assert.Equal(
            publisher.Receipts[0].MessageDeduplicationId,
            publisher.Receipts[1].MessageDeduplicationId);

        Assert.Equal(
            publisher.Receipts[0].MessageGroupId,
            publisher.Receipts[1].MessageGroupId);
    }

    [Fact]
    public async Task ClaimsForDifferentPatientsGoToDifferentOrderingGroups()
    {
        ClaimRequest first = TestData.Claim(TestData.Admission(mrn: "MRN-1"), claimId: "CLM-1");
        ClaimRequest second = TestData.Claim(TestData.Admission(mrn: "MRN-2"), claimId: "CLM-2");

        var publisher = new FakeSqsPublisher();
        await publisher.PublishAsync(new ClaimSubmission(first.ClaimId, first.GroupKey, "000000001", "x"));
        await publisher.PublishAsync(new ClaimSubmission(second.ClaimId, second.GroupKey, "000000002", "y"));

        Assert.NotEqual(publisher.Receipts[0].MessageGroupId, publisher.Receipts[1].MessageGroupId);
    }

    [Fact]
    public void EveryInterchangeThisServiceWritesIsReadableByItsOwnParser()
    {
        // The guard the worker applies before publishing: an interchange our own reader rejects is
        // one a partner will reject too, and it is cheaper to find out here.
        string edi = new X12Writer().Write(TestData.BuildClaimInterchange());

        X12Interchange parsed = new X12Reader().Read(edi);

        Assert.Equal("837", TestData.OnlySet(parsed).IdentifierCode);
    }

    [Fact]
    public void TheCanonicalJsonShapeFromTheUpstreamServiceDeserialises()
    {
        // Field-for-field the shape hl7-ingest emits, including its habit of omitting absent
        // fields rather than sending null.
        const string json = """
        {
          "event": {
            "schemaVersion": "1.0.0",
            "eventId": "evt-77",
            "messageControlId": "MSG77",
            "messageType": "ADT^A01",
            "recordedAt": "2026-08-25T14:30:05Z",
            "patient": {
              "medicalRecordNumber": "MRN-77",
              "familyName": "LUNA",
              "givenName": "IXEQUI",
              "birthDate": "1974-03-14"
            },
            "encounter": {
              "visitNumber": "V-77",
              "patientClass": "I",
              "admitDateTime": "2026-08-25T14:30:00Z"
            }
          },
          "principalDiagnosisCode": "J189",
          "serviceLines": [
            { "procedureCode": "99223", "chargeAmount": 425.50, "units": 1 }
          ]
        }
        """;

        ClaimRequest claim = ClaimRequest.From(CanonicalJson.ParseClaimRequest(json));

        Assert.Equal("V-77", claim.ClaimId);       // fell back to the visit number
        Assert.Equal("MRN-77", claim.GroupKey);
        Assert.Equal("MRN-77", claim.SubscriberMemberId);
        Assert.Equal("21", claim.PlaceOfServiceCode); // derived from patient class I
        Assert.Equal(425.50m, claim.TotalCharge);
        Assert.Null(claim.Event.Patient?.AdministrativeSex); // omitted upstream, absent here
    }

    [Fact]
    public void MalformedJsonIsRejectedWithoutEchoingThePayload()
    {
        X12MappingException error = Assert.Throws<X12MappingException>(
            () => CanonicalJson.ParseClaimRequest("{ \"event\": "));

        // The message may be logged; a claim request is PHI, so it names a position, not content.
        Assert.DoesNotContain("event", error.Message, StringComparison.Ordinal);
        Assert.Contains("not valid JSON", error.Message, StringComparison.Ordinal);
    }
}
