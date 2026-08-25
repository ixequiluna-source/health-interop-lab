using System;
using System.Collections.Generic;
using System.Globalization;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>Loop 2010AA, the billing provider. Constant per submitting organisation.</summary>
/// <param name="OrganizationName">NM103.</param>
/// <param name="Npi">NM109 with qualifier XX.</param>
/// <param name="TaxId">REF*EI. The employer identification number.</param>
/// <param name="TaxonomyCode">PRV03 with qualifier PXC.</param>
/// <param name="AddressLine">N301. A PO box here is a hard rejection: 5010 requires a street address.</param>
/// <param name="City">N401.</param>
/// <param name="State">N402.</param>
/// <param name="PostalCode">N403. Nine digits, no hyphen, for the billing provider.</param>
public sealed record BillingProviderProfile(
    string OrganizationName,
    string Npi,
    string TaxId,
    string TaxonomyCode,
    string AddressLine,
    string City,
    string State,
    string PostalCode);

/// <summary>Loop 2010BB, the destination payer.</summary>
/// <param name="Name">NM103.</param>
/// <param name="Id">NM109 with qualifier PI.</param>
/// <param name="ClaimFilingIndicator">SBR09. <c>CI</c> commercial, <c>MC</c> Medicaid, <c>MB</c> Medicare Part B.</param>
public sealed record PayerProfile(string Name, string Id, string ClaimFilingIndicator);

/// <summary>
/// Everything about a trading partner relationship that does not change from claim to claim.
/// </summary>
/// <param name="SenderQualifier">ISA05. <c>ZZ</c> = mutually defined, which is what most partners use.</param>
/// <param name="SenderId">ISA06.</param>
/// <param name="ReceiverQualifier">ISA07.</param>
/// <param name="ReceiverId">ISA08.</param>
/// <param name="UsageIndicator">
/// ISA15. <c>T</c> or <c>P</c>. This is the single most consequential character in the file: a
/// production indicator on a test batch bills real money, and a test indicator on a production
/// batch means the claims are silently discarded and nobody is paid.
/// </param>
/// <param name="SubmitterName">Loop 1000A NM103.</param>
/// <param name="SubmitterId">Loop 1000A NM109 with qualifier 46, and GS02.</param>
/// <param name="SubmitterContactName">PER02.</param>
/// <param name="SubmitterContactPhone">PER04.</param>
/// <param name="ReceiverName">Loop 1000B NM103.</param>
/// <param name="ReceiverEtin">Loop 1000B NM109 with qualifier 46, and GS03.</param>
/// <param name="BillingProvider">Loop 2010AA.</param>
/// <param name="Payer">Loop 2010BB.</param>
public sealed record TradingPartnerProfile(
    string SenderQualifier,
    string SenderId,
    string ReceiverQualifier,
    string ReceiverId,
    string UsageIndicator,
    string SubmitterName,
    string SubmitterId,
    string SubmitterContactName,
    string SubmitterContactPhone,
    string ReceiverName,
    string ReceiverEtin,
    BillingProviderProfile BillingProvider,
    PayerProfile Payer);

/// <summary>One 2400 service line.</summary>
/// <param name="ProcedureCode">SV101-2.</param>
/// <param name="ChargeAmount">SV102.</param>
/// <param name="Units">SV104.</param>
/// <param name="Modifiers">SV101-3 through SV101-6. At most four.</param>
/// <param name="ServiceDate">DTP*472 as CCYYMMDD; null falls back to the claim service date.</param>
public sealed record ServiceLine(
    string ProcedureCode,
    decimal ChargeAmount,
    decimal Units,
    IReadOnlyList<string>? Modifiers = null,
    string? ServiceDate = null);

/// <summary>
/// A validated claim, ready to be encoded.
/// </summary>
/// <remarks>
/// Validation happens once, in <see cref="From"/>, and everything downstream can assume the
/// invariants hold. The alternative — checking for missing identifiers inside the segment builder —
/// produces a builder that is half validator and error messages that talk about NM109 rather than
/// about the field the sending system actually failed to populate.
/// </remarks>
public sealed record ClaimRequest(
    AdmissionEvent Event,
    string ClaimId,
    string PlaceOfServiceCode,
    string ClaimFrequencyCode,
    string PrincipalDiagnosisCode,
    IReadOnlyList<ServiceLine> ServiceLines,
    string SubscriberMemberId,
    string? SubscriberGroupNumber = null)
{
    /// <summary>CLM01 is an AN field with a maximum length of 38.</summary>
    public const int MaxClaimIdLength = 38;

    /// <summary>SV101 carries the procedure code plus at most four modifiers.</summary>
    public const int MaxModifiers = 4;

    /// <summary>
    /// CLM02. The sum of the line charges, because that is what CLM02 is defined to be — payers
    /// edit on the equality and reject the claim when it does not hold.
    /// </summary>
    public decimal TotalCharge
    {
        get
        {
            decimal total = 0m;
            foreach (ServiceLine line in ServiceLines)
            {
                total += line.ChargeAmount;
            }

            return decimal.Round(total, 2, MidpointRounding.AwayFromZero);
        }
    }

    /// <summary>
    /// The ordering key for downstream queueing.
    /// </summary>
    /// <remarks>
    /// The medical record number, mirroring the Kafka partition key the upstream HL7 service uses.
    /// Keeping the same key across the pipeline means an original claim and its later replacement
    /// (CLM05-3 = 7) cannot be reordered relative to one another, in exactly the same way that an
    /// A01 admit and its A08 update cannot be.
    /// </remarks>
    public string GroupKey
    {
        get
        {
            string? mrn = Event.Patient?.MedicalRecordNumber;
            return string.IsNullOrWhiteSpace(mrn) ? ClaimId : mrn;
        }
    }

    /// <summary>
    /// Validates a joined claim request document and fills in the defaults that can be derived
    /// from the admission event.
    /// </summary>
    /// <exception cref="X12MappingException">Something a claim cannot be built without is absent.</exception>
    public static ClaimRequest From(ClaimRequestDocument document)
    {
        if (document is null)
        {
            throw new ArgumentNullException(nameof(document));
        }

        AdmissionEvent admissionEvent = document.Event ?? throw Missing("event");
        AdmissionPatient patient = admissionEvent.Patient ?? throw Missing("event.patient");
        AdmissionEncounter encounter = admissionEvent.Encounter ?? throw Missing("event.encounter");

        string mrn = Require(patient.MedicalRecordNumber, "event.patient.medicalRecordNumber");

        // NM103 of loop 2010BA is required. Checked here rather than in the segment builder so the
        // error names the field the sending system failed to populate, not the segment it lands in.
        Require(patient.FamilyName, "event.patient.familyName");

        // CLM01 is the patient control number, which is what PV1-19 (the visit number) means.
        // Falling back to the event id keeps the pipeline moving for senders that do not populate
        // PV1-19, at the cost of a claim id nobody in the billing office recognises — so the
        // fallback is last, not first.
        string claimId = FirstNonBlank(document.ClaimId, encounter.VisitNumber, admissionEvent.EventId)
            ?? throw Missing("claimId (and neither event.encounter.visitNumber nor event.eventId is populated)");

        if (claimId.Length > MaxClaimIdLength)
        {
            throw new X12MappingException(
                X12ErrorCode.UnrepresentableValue,
                $"claimId '{claimId}' is {claimId.Length.ToString(CultureInfo.InvariantCulture)} characters; CLM01 allows {MaxClaimIdLength.ToString(CultureInfo.InvariantCulture)}. Truncating it would break the link back to the patient account, so it is refused.");
        }

        string diagnosis = Require(document.PrincipalDiagnosisCode, "principalDiagnosisCode");
        if (diagnosis.IndexOf('.') >= 0)
        {
            throw new X12MappingException(
                X12ErrorCode.UnrepresentableValue,
                $"principalDiagnosisCode '{diagnosis}' contains a decimal point. ICD-10-CM codes are carried in X12 without one; sending 'J18.9' instead of 'J189' is rejected by every payer edit.");
        }

        List<ServiceLineDocument>? lineDocuments = document.ServiceLines;
        if (lineDocuments is null || lineDocuments.Count == 0)
        {
            throw Missing("serviceLines (an admission event carries no charges; they are joined from charge capture upstream)");
        }

        var lines = new List<ServiceLine>(lineDocuments.Count);
        for (int i = 0; i < lineDocuments.Count; i++)
        {
            ServiceLineDocument line = lineDocuments[i];
            string label = $"serviceLines[{i.ToString(CultureInfo.InvariantCulture)}]";

            string procedure = Require(line.ProcedureCode, label + ".procedureCode");

            if (line.ChargeAmount <= 0m)
            {
                throw new X12MappingException(
                    X12ErrorCode.UnrepresentableValue,
                    $"{label}.chargeAmount is {X12Numbers.Money(line.ChargeAmount)}; a service line must carry a positive charge.");
            }

            var modifiers = new List<string>();
            List<string>? rawModifiers = line.Modifiers;
            if (rawModifiers is not null)
            {
                foreach (string? modifier in rawModifiers)
                {
                    if (!string.IsNullOrWhiteSpace(modifier))
                    {
                        modifiers.Add(modifier.Trim());
                    }
                }
            }

            if (modifiers.Count > MaxModifiers)
            {
                throw new X12MappingException(
                    X12ErrorCode.UnrepresentableValue,
                    $"{label} carries {modifiers.Count.ToString(CultureInfo.InvariantCulture)} modifiers; SV101 has room for {MaxModifiers.ToString(CultureInfo.InvariantCulture)}.");
            }

            string? serviceDate = null;
            if (!string.IsNullOrWhiteSpace(line.ServiceDate))
            {
                serviceDate = X12Dates.ToDate8(line.ServiceDate)
                    ?? throw new X12MappingException(
                        X12ErrorCode.UnrepresentableValue,
                        $"{label}.serviceDate '{line.ServiceDate}' is not a full calendar date. DTP*472 is D8; a partial date cannot be widened without inventing a day of service.");
            }

            lines.Add(new ServiceLine(
                procedure,
                line.ChargeAmount,
                line.Units ?? 1m,
                modifiers,
                serviceDate));
        }

        // The place of service drives payment. Defaulting it from the HL7 patient class is better
        // than defaulting it to "office", which is what an unconfigured integration silently bills.
        string placeOfService = FirstNonBlank(document.PlaceOfServiceCode)
            ?? PlaceOfServiceFor(encounter.PatientClass);

        string frequency = FirstNonBlank(document.ClaimFrequencyCode) ?? "1";

        string memberId = FirstNonBlank(document.SubscriberMemberId) ?? mrn;

        return new ClaimRequest(
            admissionEvent,
            claimId,
            placeOfService,
            frequency,
            diagnosis,
            lines,
            memberId,
            FirstNonBlank(document.SubscriberGroupNumber));
    }

    /// <summary>
    /// Maps HL7 PV1-2 onto a CMS place of service code.
    /// </summary>
    /// <remarks>
    /// Only the three classes the upstream service actually forwards are mapped. Anything else
    /// falls through to 11 (office), which is the conservative choice: it is the lowest-paying of
    /// the three and therefore the one whose error is caught in reconciliation rather than in an
    /// overpayment recovery two quarters later.
    /// </remarks>
    private static string PlaceOfServiceFor(string? patientClass) =>
        (patientClass ?? string.Empty).Trim().ToUpperInvariant() switch
        {
            "I" => "21", // inpatient hospital
            "E" => "23", // emergency room, hospital
            _ => "11",   // office
        };

    private static string? FirstNonBlank(params string?[] candidates)
    {
        foreach (string? candidate in candidates)
        {
            if (!string.IsNullOrWhiteSpace(candidate))
            {
                return candidate.Trim();
            }
        }

        return null;
    }

    private static string Require(string? value, string field) =>
        string.IsNullOrWhiteSpace(value) ? throw Missing(field) : value.Trim();

    private static X12MappingException Missing(string field) =>
        new(X12ErrorCode.MissingRequiredData, $"'{field}' is absent, and a professional claim cannot be built without it.");
}

/// <summary>
/// The three control numbers of one submission, plus the batch reference that goes in BHT03.
/// </summary>
/// <remarks>
/// All three are derived from one monotonic sequence value so that an operator holding a partner's
/// 999 rejection — which quotes GS06 — can find the interchange (ISA13) and the file it came from
/// without a lookup table.
/// </remarks>
public sealed record X12ControlNumbers(
    string Interchange,
    string Group,
    string TransactionSet,
    string BatchReference)
{
    /// <summary>ISA13 is an N0 field nine characters wide, so the sequence cannot exceed 999999999.</summary>
    public const long MaxSequence = 999999999L;

    public static X12ControlNumbers From(long sequence, string transactionSetControlNumber = "0001")
    {
        if (sequence < 1 || sequence > MaxSequence)
        {
            throw new ArgumentOutOfRangeException(
                nameof(sequence),
                sequence,
                $"An interchange control number must be between 1 and {MaxSequence.ToString(CultureInfo.InvariantCulture)}; ISA13 is nine digits wide.");
        }

        string interchange = sequence.ToString("D9", CultureInfo.InvariantCulture);
        return new X12ControlNumbers(
            interchange,
            sequence.ToString(CultureInfo.InvariantCulture),
            transactionSetControlNumber,
            "B" + interchange);
    }
}

/// <summary>
/// Builds a 005010X222A1 professional claim (837P) from a canonical admission event.
/// </summary>
/// <remarks>
/// <para>
/// The hierarchy this produces is the simple, overwhelmingly common one: a billing provider
/// (HL level 20) with a single subscriber child (HL level 22) who is also the patient. When the
/// subscriber is the patient, 5010 forbids the 2000C patient loop, which is why the subscriber HL
/// carries child code 0 rather than 1. Emitting a patient loop anyway is a 999 rejection, not a
/// warning.
/// </para>
/// <para>
/// Envelope counts are not built here at all. The writer computes SE01, GE01 and IEA01 from what it
/// actually serialises, so adding a segment to this builder cannot desynchronise the envelope.
/// </para>
/// </remarks>
public sealed class Claim837PBuilder
{
    /// <summary>ST03 and GS08: the 837P implementation guide identifier.</summary>
    public const string ImplementationReference = "005010X222A1";

    /// <summary>ST01.</summary>
    public const string TransactionSetIdentifier = "837";

    /// <summary>GS01: health care claim.</summary>
    public const string FunctionalIdentifierCode = "HC";

    /// <summary>ISA12: the interchange control version.</summary>
    public const string InterchangeVersion = "00501";

    private readonly TradingPartnerProfile _profile;

    public Claim837PBuilder(TradingPartnerProfile profile)
    {
        _profile = profile ?? throw new ArgumentNullException(nameof(profile));
    }

    /// <summary>Builds a single-claim interchange.</summary>
    /// <exception cref="X12MappingException">The event lacks something the claim requires.</exception>
    public X12Interchange Build(
        ClaimRequest claim,
        X12ControlNumbers controls,
        DateTimeOffset submittedAt,
        X12Delimiters? delimiters = null)
    {
        if (claim is null)
        {
            throw new ArgumentNullException(nameof(claim));
        }

        if (controls is null)
        {
            throw new ArgumentNullException(nameof(controls));
        }

        AdmissionPatient patient = claim.Event.Patient
            ?? throw new X12MappingException(X12ErrorCode.MissingRequiredData, "'event.patient' is absent.");
        AdmissionEncounter encounter = claim.Event.Encounter
            ?? throw new X12MappingException(X12ErrorCode.MissingRequiredData, "'event.encounter' is absent.");

        string serviceDate = X12Dates.ToDate8(encounter.AdmitDateTime)
            ?? throw new X12MappingException(
                X12ErrorCode.UnrepresentableValue,
                $"'event.encounter.admitDateTime' ('{encounter.AdmitDateTime}') does not carry a full calendar date, and the claim has no other source for the date of service.");

        string groupDate = submittedAt.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        string groupTime = submittedAt.ToString("HHmm", CultureInfo.InvariantCulture);

        var segments = new List<X12Segment>(32);

        // BHT06 = CH: the transaction contains chargeable claims rather than encounter reporting.
        // BHT02 = 00: original, as opposed to 18 (reissue).
        segments.Add(new X12Segment("BHT", "0019", "00", controls.BatchReference, groupDate, groupTime, "CH"));

        // Loop 1000A — submitter. Qualifier 46 is an electronic transmitter identification number.
        segments.Add(new X12Segment(
            "NM1", "41", "2", _profile.SubmitterName, "", "", "", "", "46", _profile.SubmitterId));
        segments.Add(new X12Segment(
            "PER", "IC", _profile.SubmitterContactName, "TE", _profile.SubmitterContactPhone));

        // Loop 1000B — receiver.
        segments.Add(new X12Segment(
            "NM1", "40", "2", _profile.ReceiverName, "", "", "", "", "46", _profile.ReceiverEtin));

        // Loop 2000A — billing provider. HL01 = 1 (this hierarchy's first node), HL02 empty (no
        // parent), HL03 = 20 (information source / billing provider), HL04 = 1 (has children).
        segments.Add(new X12Segment("HL", "1", "", "20", "1"));
        segments.Add(new X12Segment("PRV", "BI", "PXC", _profile.BillingProvider.TaxonomyCode));

        BillingProviderProfile billing = _profile.BillingProvider;
        segments.Add(new X12Segment(
            "NM1", "85", "2", billing.OrganizationName, "", "", "", "", "XX", billing.Npi));
        segments.Add(new X12Segment("N3", billing.AddressLine));
        segments.Add(new X12Segment("N4", billing.City, billing.State, billing.PostalCode));
        segments.Add(new X12Segment("REF", "EI", billing.TaxId));

        // Loop 2000B — subscriber. HL01 = 2, HL02 = 1 (parented to the billing provider),
        // HL03 = 22 (subscriber), HL04 = 0. The zero is load bearing: the subscriber is the
        // patient, so there is no 2000C loop beneath this node.
        segments.Add(new X12Segment("HL", "2", "1", "22", "0"));

        // SBR01 = P (payer is primary), SBR02 = 18 (the subscriber is the patient),
        // SBR09 = the claim filing indicator. SBR04..SBR08 are unused in 5010 professional.
        segments.Add(new X12Segment(
            "SBR",
            "P",
            "18",
            claim.SubscriberGroupNumber ?? string.Empty,
            "",
            "",
            "",
            "",
            "",
            _profile.Payer.ClaimFilingIndicator));

        // Loop 2010BA — subscriber name. Qualifier MI is the member identification number.
        segments.Add(new X12Segment(
            "NM1",
            "IL",
            "1",
            patient.FamilyName ?? string.Empty,
            patient.GivenName ?? string.Empty,
            "",
            "",
            "",
            "MI",
            claim.SubscriberMemberId));

        // DMG is required when the subscriber is the patient — but only when we actually know the
        // birth date. A partial date is omitted rather than padded: DMG02 is D8, and padding
        // 1974-03 to 19740301 invents a birthday that the payer will match against eligibility.
        string? birthDate = X12Dates.ToDate8(patient.BirthDate);
        if (birthDate is not null)
        {
            segments.Add(new X12Segment("DMG", "D8", birthDate, MapAdministrativeSex(patient.AdministrativeSex)));
        }

        // Loop 2010BB — payer. Qualifier PI is a payer identification number.
        segments.Add(new X12Segment(
            "NM1", "PR", "2", _profile.Payer.Name, "", "", "", "", "PI", _profile.Payer.Id));

        // Loop 2300 — the claim. CLM05 is a composite: facility code (place of service), the
        // facility code qualifier B, and the claim frequency code. Writing it as three elements
        // instead of one composite is a structural rejection, and writing it as a single string
        // with a hard-coded colon breaks the moment a partner uses a different component separator.
        segments.Add(new X12Segment(
            "CLM",
            claim.ClaimId,
            X12Numbers.Money(claim.TotalCharge),
            "",
            "",
            X12Element.Composite(claim.PlaceOfServiceCode, "B", claim.ClaimFrequencyCode),
            "Y",  // CLM06 provider signature on file
            "A",  // CLM07 provider accepts assignment
            "Y",  // CLM08 benefits assignment certification
            "Y")); // CLM09 release of information

        // DTP*435 is the admission date and is required for an inpatient professional claim.
        // Send the time as well when the sender gave us one: DT carries CCYYMMDDHHMM, D8 does not.
        if (IsInpatient(encounter.PatientClass))
        {
            string? admitDateTime = X12Dates.ToDateTime12(encounter.AdmitDateTime);
            segments.Add(admitDateTime is not null
                ? new X12Segment("DTP", "435", "DT", admitDateTime)
                : new X12Segment("DTP", "435", "D8", serviceDate));
        }

        // ABK is the principal ICD-10-CM diagnosis. In 4010 this was BK; a submitter that never
        // updated the qualifier passes syntax validation and fails every payer's code-set edit.
        segments.Add(new X12Segment("HI", X12Element.Composite("ABK", claim.PrincipalDiagnosisCode)));

        int lineNumber = 1;
        foreach (ServiceLine line in claim.ServiceLines)
        {
            segments.Add(new X12Segment("LX", lineNumber.ToString(CultureInfo.InvariantCulture)));
            segments.Add(new X12Segment(
                "SV1",
                ProcedureComposite(line),
                X12Numbers.Money(line.ChargeAmount),
                "UN",
                X12Numbers.Quantity(line.Units),
                "",
                "",
                X12Element.Composite("1"))); // SV107, diagnosis code pointer to the HI composite
            segments.Add(new X12Segment("DTP", "472", "D8", line.ServiceDate ?? serviceDate));
            lineNumber++;
        }

        var transactionSet = new X12TransactionSet(
            TransactionSetIdentifier,
            controls.TransactionSet,
            ImplementationReference,
            segments);

        var groupHeader = new X12GroupHeader(
            FunctionalIdentifierCode,
            _profile.SubmitterId,
            _profile.ReceiverEtin,
            groupDate,
            groupTime,
            controls.Group,
            "X",
            ImplementationReference);

        var interchangeHeader = new X12InterchangeHeader(
            "00",
            string.Empty,
            "00",
            string.Empty,
            _profile.SenderQualifier,
            _profile.SenderId,
            _profile.ReceiverQualifier,
            _profile.ReceiverId,
            // ISA09 is YYMMDD — a two-digit year. The ISA was never widened, so this is correct
            // rather than a bug, and GS04 immediately below carries the full CCYYMMDD.
            submittedAt.ToString("yyMMdd", CultureInfo.InvariantCulture),
            submittedAt.ToString("HHmm", CultureInfo.InvariantCulture),
            InterchangeVersion,
            controls.Interchange,
            "0",
            _profile.UsageIndicator);

        var group = new X12FunctionalGroup(groupHeader, new[] { transactionSet });

        return new X12Interchange(interchangeHeader, new[] { group })
        {
            Delimiters = delimiters ?? X12Delimiters.Default,
        };
    }

    /// <summary>
    /// SV101: qualifier HC (HCPCS/CPT), the procedure code, then up to four modifiers.
    /// </summary>
    private static X12Element ProcedureComposite(ServiceLine line)
    {
        var components = new List<string>(6) { "HC", line.ProcedureCode };
        IReadOnlyList<string>? modifiers = line.Modifiers;
        if (modifiers is not null)
        {
            foreach (string modifier in modifiers)
            {
                if (!string.IsNullOrWhiteSpace(modifier))
                {
                    components.Add(modifier.Trim());
                }
            }
        }

        return X12Element.Composite(components.ToArray());
    }

    /// <summary>
    /// Maps HL7 PID-8 onto the DMG03 value set.
    /// </summary>
    /// <remarks>
    /// HL7 allows A (ambiguous), N (not applicable), O (other) and U (unknown) alongside M and F.
    /// X12 005010 DMG03 allows only F, M and U. Passing an HL7-only code straight through is a
    /// syntax rejection of the whole transaction set, so everything that is not unambiguously male
    /// or female becomes U — which is a true statement about what the claim knows, not a guess.
    /// </remarks>
    public static string MapAdministrativeSex(string? hl7Value)
    {
        if (string.IsNullOrWhiteSpace(hl7Value))
        {
            return "U";
        }

        return hl7Value.Trim().ToUpperInvariant() switch
        {
            "M" => "M",
            "F" => "F",
            _ => "U",
        };
    }

    /// <summary>HL7 PV1-2 inpatient classes.</summary>
    public static bool IsInpatient(string? patientClass) =>
        string.Equals(patientClass, "I", StringComparison.OrdinalIgnoreCase);
}
