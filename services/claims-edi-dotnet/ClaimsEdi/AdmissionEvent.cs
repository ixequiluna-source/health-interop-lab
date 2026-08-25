using System;
using System.Collections.Generic;
using System.Text.Json;

namespace Firmus.Interop.ClaimsEdi;

/// <summary>
/// The canonical admission event published by <c>hl7-ingest</c>.
/// </summary>
/// <remarks>
/// <para>
/// This mirrors <c>ai.firmus.interop.hl7.AdmissionEvent</c>. Two properties of that contract shape
/// this type:
/// </para>
/// <list type="bullet">
/// <item>
/// The producer <em>omits</em> absent fields rather than emitting <c>null</c>, so every property
/// here is nullable and "absent" and "present but empty" are both possible and both meaningful.
/// </item>
/// <item>
/// Dates may be partial (<c>1974</c>, <c>1974-03</c>, <c>1974-03-14</c>) because the producer
/// widens rather than pads. See <see cref="X12Dates"/> for why that matters on this side.
/// </item>
/// </list>
/// <para>
/// These are deliberately plain mutable DTOs rather than records with positional constructors. The
/// JSON boundary is the one place where the shape is dictated by someone else, and keeping the
/// deserialisation target dumb means every validation decision happens in one visible place —
/// <see cref="ClaimRequest.From"/> — instead of being smeared across constructor preconditions
/// that <c>System.Text.Json</c> may or may not invoke the way you expect.
/// </para>
/// </remarks>
public sealed class AdmissionEvent
{
    public string? SchemaVersion { get; set; }

    public string? EventId { get; set; }

    public string? MessageControlId { get; set; }

    public string? MessageType { get; set; }

    public string? SendingApplication { get; set; }

    public string? SendingFacility { get; set; }

    public string? RecordedAt { get; set; }

    public AdmissionPatient? Patient { get; set; }

    public AdmissionEncounter? Encounter { get; set; }
}

/// <summary>Patient identity as carried by the canonical event.</summary>
public sealed class AdmissionPatient
{
    public string? MedicalRecordNumber { get; set; }

    public List<string>? OtherIdentifiers { get; set; }

    public string? FamilyName { get; set; }

    public string? GivenName { get; set; }

    /// <summary>May be a partial date. See <see cref="X12Dates.ToDate8"/>.</summary>
    public string? BirthDate { get; set; }

    /// <summary>HL7 PID-8. Wider than the X12 DMG03 value set; see <c>Claim837PBuilder</c>.</summary>
    public string? AdministrativeSex { get; set; }
}

/// <summary>Encounter context as carried by the canonical event.</summary>
public sealed class AdmissionEncounter
{
    /// <summary>HL7 PV1-19. This is the patient account number, which is what CLM01 means.</summary>
    public string? VisitNumber { get; set; }

    /// <summary>HL7 PV1-2. <c>I</c> inpatient, <c>O</c> outpatient, <c>E</c> emergency.</summary>
    public string? PatientClass { get; set; }

    public string? AdmitDateTime { get; set; }

    public string? AttendingClinician { get; set; }

    public string? PointOfCare { get; set; }

    public string? Room { get; set; }

    public string? Bed { get; set; }

    public string? Facility { get; set; }
}

/// <summary>
/// The unit of work this service consumes: one canonical admission event joined with the charge
/// detail that a claim needs and an admission event cannot carry.
/// </summary>
/// <remarks>
/// An ADT message describes an admission; it says nothing about what was done or what it cost.
/// Diagnoses and charges come from coding and charge capture. Rather than pretend otherwise — or,
/// worse, default a procedure code so that the pipeline "works" end to end — this service consumes
/// the joined document and fails loudly when the charge detail is absent.
/// </remarks>
public sealed class ClaimRequestDocument
{
    public AdmissionEvent? Event { get; set; }

    /// <summary>CLM01. Defaults to the encounter's visit number when omitted.</summary>
    public string? ClaimId { get; set; }

    /// <summary>CLM05-1, the place of service code. <c>11</c> office, <c>21</c> inpatient hospital.</summary>
    public string? PlaceOfServiceCode { get; set; }

    /// <summary>CLM05-3, the claim frequency code. <c>1</c> original, <c>7</c> replacement, <c>8</c> void.</summary>
    public string? ClaimFrequencyCode { get; set; }

    /// <summary>HI ABK — the principal ICD-10-CM diagnosis, without its decimal point.</summary>
    public string? PrincipalDiagnosisCode { get; set; }

    /// <summary>NM109 of loop 2010BA. Defaults to the medical record number.</summary>
    public string? SubscriberMemberId { get; set; }

    /// <summary>SBR03.</summary>
    public string? SubscriberGroupNumber { get; set; }

    public List<ServiceLineDocument>? ServiceLines { get; set; }
}

/// <summary>One 2400 service line.</summary>
public sealed class ServiceLineDocument
{
    /// <summary>SV101-2, the CPT or HCPCS code.</summary>
    public string? ProcedureCode { get; set; }

    /// <summary>SV101-3 onwards. At most four.</summary>
    public List<string>? Modifiers { get; set; }

    /// <summary>SV102.</summary>
    public decimal ChargeAmount { get; set; }

    /// <summary>SV104. Defaults to 1 when omitted.</summary>
    public decimal? Units { get; set; }

    /// <summary>DTP*472. Defaults to the encounter's admission date.</summary>
    public string? ServiceDate { get; set; }
}

/// <summary>
/// The JSON boundary.
/// </summary>
public static class CanonicalJson
{
    /// <summary>
    /// Options matching the producer's encoding.
    /// </summary>
    /// <remarks>
    /// <see cref="JsonSerializerDefaults.Web"/> supplies camelCase naming and case-insensitive
    /// matching, which is exactly what the Java producer emits. No source generation and no custom
    /// converters: the shape is small, it is someone else's contract, and the reflection cost is
    /// irrelevant next to the SQS round trip.
    /// </remarks>
    public static JsonSerializerOptions Options { get; } = new(JsonSerializerDefaults.Web)
    {
        AllowTrailingCommas = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };

    /// <summary>Parses a joined claim request document.</summary>
    /// <exception cref="X12MappingException">The payload is absent or not valid JSON.</exception>
    public static ClaimRequestDocument ParseClaimRequest(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new X12MappingException(X12ErrorCode.MissingRequiredData, "The claim request payload is empty.");
        }

        ClaimRequestDocument? document;
        try
        {
            document = JsonSerializer.Deserialize<ClaimRequestDocument>(json, Options);
        }
        catch (JsonException ex)
        {
            // The message carries the line and byte position but never the payload: a claim request
            // is PHI and exception messages end up in logs, which are rarely under the same
            // retention and access controls as the clinical store.
            throw new X12MappingException(
                X12ErrorCode.MissingRequiredData,
                $"The claim request payload is not valid JSON (line {ex.LineNumber}, byte {ex.BytePositionInLine}).",
                ex);
        }

        return document ?? throw new X12MappingException(
            X12ErrorCode.MissingRequiredData,
            "The claim request payload deserialised to null.");
    }

    /// <summary>Parses a bare canonical admission event, for tooling and tests.</summary>
    /// <exception cref="X12MappingException">The payload is absent or not valid JSON.</exception>
    public static AdmissionEvent ParseAdmissionEvent(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            throw new X12MappingException(X12ErrorCode.MissingRequiredData, "The admission event payload is empty.");
        }

        AdmissionEvent? admissionEvent;
        try
        {
            admissionEvent = JsonSerializer.Deserialize<AdmissionEvent>(json, Options);
        }
        catch (JsonException ex)
        {
            throw new X12MappingException(
                X12ErrorCode.MissingRequiredData,
                $"The admission event payload is not valid JSON (line {ex.LineNumber}, byte {ex.BytePositionInLine}).",
                ex);
        }

        return admissionEvent ?? throw new X12MappingException(
            X12ErrorCode.MissingRequiredData,
            "The admission event payload deserialised to null.");
    }
}
