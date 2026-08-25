using System;
using System.Collections.Generic;

namespace Firmus.Interop.ClaimsEdi.Tests;

/// <summary>
/// Fixtures shared across the suite.
/// </summary>
/// <remarks>
/// Every value here is synthetic. The NPI is one of CMS's documented test numbers and the payer id
/// is not a real one — putting a live NPI in a repository is a small mistake with a long tail.
/// </remarks>
internal static class TestData
{
    public const string Mrn = "MRN-4417";
    public const string ClaimId = "CLM-1001";

    /// <summary>A fixed submission instant, so ISA09/ISA10 and GS04/GS05 are deterministic.</summary>
    public static DateTimeOffset SubmittedAt { get; } =
        new DateTimeOffset(2026, 8, 25, 14, 30, 0, TimeSpan.Zero);

    public static BillingProviderProfile Billing { get; } = new(
        "FIRMUS HEALTH GROUP",
        "1234567893",
        "581234567",
        "207Q00000X",
        "1 HOSPITAL WAY",
        "ATLANTA",
        "GA",
        "303011234");

    public static PayerProfile Payer { get; } = new("ACME HEALTH PLAN", "60054", "CI");

    public static TradingPartnerProfile Profile { get; } = new(
        "ZZ",
        "FIRMUSHEALTH",
        "ZZ",
        "CLEARINGHOUSE",
        "T",
        "FIRMUS HEALTH GROUP",
        "FIRMUS01",
        "EDI OPERATIONS",
        "4045550100",
        "ACME CLEARINGHOUSE",
        "CH0001",
        Billing,
        Payer);

    public static AdmissionEvent Admission(
        string? familyName = "LUNA",
        string? givenName = "IXEQUI",
        string? birthDate = "1974-03-14",
        string? administrativeSex = "M",
        string? patientClass = "I",
        string? admitDateTime = "2026-08-25T14:30:00Z",
        string? mrn = Mrn,
        string? visitNumber = "V-90210") =>
        new()
        {
            SchemaVersion = "1.0.0",
            EventId = "evt-0001",
            MessageControlId = "MSG1",
            MessageType = "ADT^A01",
            SendingApplication = "EPIC_ADT",
            SendingFacility = "HGS",
            RecordedAt = "2026-08-25T14:30:05Z",
            Patient = new AdmissionPatient
            {
                MedicalRecordNumber = mrn,
                FamilyName = familyName,
                GivenName = givenName,
                BirthDate = birthDate,
                AdministrativeSex = administrativeSex,
            },
            Encounter = new AdmissionEncounter
            {
                VisitNumber = visitNumber,
                PatientClass = patientClass,
                AdmitDateTime = admitDateTime,
                AttendingClinician = "DR HOUSE",
                Facility = "HGS",
            },
        };

    public static List<ServiceLineDocument> OneLine() =>
        new()
        {
            new ServiceLineDocument
            {
                ProcedureCode = "99223",
                ChargeAmount = 425.50m,
                Units = 1m,
                Modifiers = new List<string> { "25" },
            },
        };

    public static ClaimRequestDocument Document(
        AdmissionEvent? admission = null,
        string? claimId = ClaimId,
        string? diagnosis = "J189",
        string? placeOfService = null,
        string? frequency = "1",
        List<ServiceLineDocument>? lines = null) =>
        new()
        {
            Event = admission ?? Admission(),
            ClaimId = claimId,
            PrincipalDiagnosisCode = diagnosis,
            PlaceOfServiceCode = placeOfService,
            ClaimFrequencyCode = frequency,
            ServiceLines = lines ?? OneLine(),
        };

    public static ClaimRequest Claim(
        AdmissionEvent? admission = null,
        string? claimId = ClaimId,
        string? diagnosis = "J189",
        string? placeOfService = null,
        string? frequency = "1",
        List<ServiceLineDocument>? lines = null) =>
        ClaimRequest.From(Document(admission, claimId, diagnosis, placeOfService, frequency, lines));

    public static X12Interchange BuildClaimInterchange(
        ClaimRequest? claim = null,
        X12Delimiters? delimiters = null,
        long sequence = 1) =>
        new Claim837PBuilder(Profile).Build(
            claim ?? Claim(),
            X12ControlNumbers.From(sequence),
            SubmittedAt,
            delimiters);

    // ---- Hand-built envelopes, for testing the writer and reader in isolation -----------------

    public static X12InterchangeHeader Header(string controlNumber = "000000001") => new(
        "00",
        string.Empty,
        "00",
        string.Empty,
        "ZZ",
        "FIRMUSHEALTH",
        "ZZ",
        "CLEARINGHOUSE",
        "260825",
        "1430",
        "00501",
        controlNumber,
        "0",
        "T");

    public static X12GroupHeader GroupHeader(string controlNumber = "1") => new(
        "HC",
        "FIRMUS01",
        "CH0001",
        "20260825",
        "1430",
        controlNumber,
        "X",
        "005010X222A1");

    /// <summary>An interchange with one group, one transaction set and the supplied body.</summary>
    public static X12Interchange Envelope(params X12Segment[] body) => new(
        Header(),
        new[]
        {
            new X12FunctionalGroup(
                GroupHeader(),
                new[] { new X12TransactionSet("837", "0001", "005010X222A1", body) }),
        });

    /// <summary>Three body segments, so SE01 is 5.</summary>
    public static X12Interchange MinimalEnvelope() => Envelope(
        new X12Segment("BHT", "0019", "00", "B000000001", "20260825", "1430", "CH"),
        new X12Segment("NM1", "41", "2", "FIRMUS HEALTH GROUP", "", "", "", "", "46", "FIRMUS01"),
        new X12Segment("SBR", "P", "18", "", "", "", "", "", "", "CI"));

    public static string MinimalEdi(X12Delimiters? delimiters = null) =>
        new X12Writer(delimiters ?? X12Delimiters.Default).Write(MinimalEnvelope());

    // ---- Segment lookup ------------------------------------------------------------------------

    public static X12TransactionSet OnlySet(X12Interchange interchange) =>
        interchange.Groups[0].TransactionSets[0];

    public static List<X12Segment> FindAll(X12TransactionSet set, string id)
    {
        var found = new List<X12Segment>();
        foreach (X12Segment segment in set.Segments)
        {
            if (string.Equals(segment.Id, id, StringComparison.Ordinal))
            {
                found.Add(segment);
            }
        }

        return found;
    }

    public static X12Segment Find(X12TransactionSet set, string id)
    {
        List<X12Segment> found = FindAll(set, id);
        return found.Count > 0
            ? found[0]
            : throw new InvalidOperationException($"No '{id}' segment in the transaction set.");
    }

    /// <summary>Finds a segment by id and the value of its first element, e.g. NM1 with NM101=85.</summary>
    public static X12Segment Find(X12TransactionSet set, string id, string firstElement)
    {
        foreach (X12Segment segment in FindAll(set, id))
        {
            if (string.Equals(segment.Value(1), firstElement, StringComparison.Ordinal))
            {
                return segment;
            }
        }

        throw new InvalidOperationException($"No '{id}' segment with a first element of '{firstElement}'.");
    }

    public static bool Has(X12TransactionSet set, string id) => FindAll(set, id).Count > 0;

    /// <summary>Splits raw EDI into segment text, for assertions about the wire format itself.</summary>
    public static string[] RawSegments(string edi, X12Delimiters? delimiters = null) =>
        edi.Split((delimiters ?? X12Delimiters.Default).Segment, StringSplitOptions.RemoveEmptyEntries);

    public static string RawSegment(string edi, string id, X12Delimiters? delimiters = null)
    {
        X12Delimiters d = delimiters ?? X12Delimiters.Default;
        foreach (string segment in RawSegments(edi, d))
        {
            if (segment.StartsWith(id + d.Element, StringComparison.Ordinal))
            {
                return segment;
            }
        }

        throw new InvalidOperationException($"No raw '{id}' segment in the interchange.");
    }
}

/// <summary>
/// An <see cref="ISqsPublisher"/> that records what it was asked to send.
/// </summary>
/// <remarks>
/// The whole point of the publisher being an interface: the pipeline is exercised end to end in CI
/// with no AWS credentials, no network and no LocalStack container.
/// </remarks>
internal sealed class FakeSqsPublisher : ISqsPublisher
{
    private int _counter;

    public List<ClaimSubmission> Submissions { get; } = new();

    public List<ClaimPublishReceipt> Receipts { get; } = new();

    public System.Threading.Tasks.Task<ClaimPublishReceipt> PublishAsync(
        ClaimSubmission submission,
        System.Threading.CancellationToken cancellationToken = default)
    {
        Submissions.Add(submission);

        _counter++;
        var receipt = new ClaimPublishReceipt(
            "fake-" + _counter.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ClaimDeduplication.GroupIdFor(submission.GroupKey),
            ClaimDeduplication.DeduplicationIdFor(submission.ClaimId));

        Receipts.Add(receipt);
        return System.Threading.Tasks.Task.FromResult(receipt);
    }
}
