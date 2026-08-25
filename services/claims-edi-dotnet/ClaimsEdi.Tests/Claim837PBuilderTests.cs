using System;
using System.Collections.Generic;
using Xunit;

namespace Firmus.Interop.ClaimsEdi.Tests;

public sealed class Claim837PBuilderTests
{
    private static X12TransactionSet Set(ClaimRequest? claim = null) =>
        TestData.OnlySet(TestData.BuildClaimInterchange(claim));

    [Fact]
    public void TheTransactionSetDeclaresThe837PImplementationGuide()
    {
        X12Interchange interchange = TestData.BuildClaimInterchange();

        Assert.Equal("837", TestData.OnlySet(interchange).IdentifierCode);
        Assert.Equal("005010X222A1", TestData.OnlySet(interchange).ImplementationReference);
        Assert.Equal("005010X222A1", interchange.Groups[0].Header.VersionReleaseCode);
        Assert.Equal("HC", interchange.Groups[0].Header.FunctionalIdentifierCode);
    }

    [Fact]
    public void TheBillingProviderHierarchicalLevelIsTheRootAndHasChildren()
    {
        X12Segment billing = TestData.FindAll(Set(), "HL")[0];

        Assert.Equal("1", billing.Value(1));                  // HL01 hierarchical id
        Assert.Equal(string.Empty, billing.Value(2));         // HL02 no parent
        Assert.Equal("20", billing.Value(3));                 // HL03 billing provider
        Assert.Equal("1", billing.Value(4));                  // HL04 has subordinates
    }

    [Fact]
    public void TheSubscriberHierarchicalLevelIsParentedToTheBillingProviderAndHasNoChildren()
    {
        X12Segment subscriber = TestData.FindAll(Set(), "HL")[1];

        Assert.Equal("2", subscriber.Value(1));
        Assert.Equal("1", subscriber.Value(2));               // HL02 parents it to HL01 = 1
        Assert.Equal("22", subscriber.Value(3));              // HL03 subscriber

        // HL04 = 0 is load bearing. The subscriber is the patient, so 5010 forbids a 2000C patient
        // loop; declaring children and not sending them is a structural rejection.
        Assert.Equal("0", subscriber.Value(4));
    }

    [Fact]
    public void TheHierarchyContainsExactlyTwoLevels()
    {
        Assert.Equal(2, TestData.FindAll(Set(), "HL").Count);
    }

    [Fact]
    public void TheBillingProviderIsIdentifiedByNpiAndTaxId()
    {
        X12TransactionSet set = Set();
        X12Segment provider = TestData.Find(set, "NM1", "85");

        Assert.Equal("2", provider.Value(2));                 // non-person entity
        Assert.Equal("FIRMUS HEALTH GROUP", provider.Value(3));
        Assert.Equal("XX", provider.Value(8));                // NPI qualifier
        Assert.Equal("1234567893", provider.Value(9));

        X12Segment taxId = TestData.Find(set, "REF");
        Assert.Equal("EI", taxId.Value(1));
        Assert.Equal("581234567", taxId.Value(2));
    }

    [Fact]
    public void TheSubscriberCarriesTheMemberIdAndTheNameFromTheAdmissionEvent()
    {
        X12Segment subscriber = TestData.Find(Set(), "NM1", "IL");

        Assert.Equal("1", subscriber.Value(2));               // person
        Assert.Equal("LUNA", subscriber.Value(3));
        Assert.Equal("IXEQUI", subscriber.Value(4));
        Assert.Equal("MI", subscriber.Value(8));
        Assert.Equal(TestData.Mrn, subscriber.Value(9));
    }

    [Fact]
    public void ThePayerIsIdentifiedByPayerId()
    {
        X12Segment payer = TestData.Find(Set(), "NM1", "PR");

        Assert.Equal("ACME HEALTH PLAN", payer.Value(3));
        Assert.Equal("PI", payer.Value(8));
        Assert.Equal("60054", payer.Value(9));
    }

    [Fact]
    public void TheSubscriberRelationshipIsSelfAndTheFilingIndicatorComesFromThePayerProfile()
    {
        X12Segment sbr = TestData.Find(Set(), "SBR");

        Assert.Equal("P", sbr.Value(1));                      // payer responsibility: primary
        Assert.Equal("18", sbr.Value(2));                     // relationship: self
        Assert.Equal("CI", sbr.Value(9));                     // claim filing indicator
    }

    [Fact]
    public void Clm05IsACompositeOfPlaceOfServiceQualifierAndFrequency()
    {
        X12Segment claim = TestData.Find(Set(), "CLM");

        Assert.True(claim.Element(5).IsComposite);
        Assert.Equal("21", claim.Component(5, 1));            // inpatient hospital, from PV1-2 = I
        Assert.Equal("B", claim.Component(5, 2));             // facility code qualifier
        Assert.Equal("1", claim.Component(5, 3));             // original claim
    }

    [Fact]
    public void Clm02IsTheSumOfTheServiceLineCharges()
    {
        var lines = new List<ServiceLineDocument>
        {
            new() { ProcedureCode = "99223", ChargeAmount = 425.50m, Units = 1m },
            new() { ProcedureCode = "93000", ChargeAmount = 74.25m, Units = 1m },
        };

        X12TransactionSet set = Set(TestData.Claim(lines: lines));

        // Payers edit on CLM02 == sum(SV102). A cent of drift rejects the claim.
        Assert.Equal("499.75", TestData.Find(set, "CLM").Value(2));

        List<X12Segment> serviceLines = TestData.FindAll(set, "SV1");
        Assert.Equal("425.5", serviceLines[0].Value(2));
        Assert.Equal("74.25", serviceLines[1].Value(2));
    }

    [Fact]
    public void ClaimIdComesFromTheDocumentThenTheVisitNumber()
    {
        Assert.Equal(TestData.ClaimId, TestData.Find(Set(), "CLM").Value(1));

        // PV1-19 is the patient account number, which is what CLM01 means.
        X12TransactionSet fromVisit = Set(TestData.Claim(claimId: null));
        Assert.Equal("V-90210", TestData.Find(fromVisit, "CLM").Value(1));
    }

    [Fact]
    public void ServiceLinesAreNumberedAndCarryTheirOwnServiceDate()
    {
        var lines = new List<ServiceLineDocument>
        {
            new() { ProcedureCode = "99223", ChargeAmount = 425.50m, Units = 1m },
            new() { ProcedureCode = "93000", ChargeAmount = 74.25m, Units = 2m },
        };

        X12TransactionSet set = Set(TestData.Claim(lines: lines));

        List<X12Segment> lx = TestData.FindAll(set, "LX");
        Assert.Equal("1", lx[0].Value(1));
        Assert.Equal("2", lx[1].Value(1));

        List<X12Segment> sv1 = TestData.FindAll(set, "SV1");
        Assert.Equal("UN", sv1[1].Value(3));
        Assert.Equal("2", sv1[1].Value(4));

        List<X12Segment> dates = TestData.FindAll(set, "DTP");
        X12Segment serviceDate = dates[dates.Count - 1];
        Assert.Equal("472", serviceDate.Value(1));
        Assert.Equal("D8", serviceDate.Value(2));
        Assert.Equal("20260825", serviceDate.Value(3));
    }

    [Fact]
    public void Sv101CarriesTheProcedureCodeAndItsModifiers()
    {
        X12Segment line = TestData.Find(Set(), "SV1");

        Assert.Equal("HC", line.Component(1, 1));
        Assert.Equal("99223", line.Component(1, 2));
        Assert.Equal("25", line.Component(1, 3));
    }

    [Fact]
    public void ThePrincipalDiagnosisUsesTheIcd10Qualifier()
    {
        // ABK, not the 4010-era BK. A submitter that never updated the qualifier passes syntax
        // validation and fails every payer's code-set edit.
        X12Segment diagnosis = TestData.Find(Set(), "HI");

        Assert.Equal("ABK", diagnosis.Component(1, 1));
        Assert.Equal("J189", diagnosis.Component(1, 2));
    }

    [Fact]
    public void AnInpatientClaimCarriesTheAdmissionDateWithItsTime()
    {
        X12Segment admission = TestData.Find(Set(), "DTP", "435");

        Assert.Equal("DT", admission.Value(2));
        Assert.Equal("202608251430", admission.Value(3));
    }

    [Fact]
    public void AnOutpatientClaimCarriesNoAdmissionDate()
    {
        X12TransactionSet set = Set(TestData.Claim(TestData.Admission(patientClass: "O")));

        foreach (X12Segment segment in TestData.FindAll(set, "DTP"))
        {
            Assert.NotEqual("435", segment.Value(1));
        }
    }

    [Fact]
    public void ThePlaceOfServiceIsDerivedFromThePatientClassWhenNotSupplied()
    {
        Assert.Equal("21", TestData.Find(Set(TestData.Claim(TestData.Admission(patientClass: "I"))), "CLM").Component(5, 1));
        Assert.Equal("23", TestData.Find(Set(TestData.Claim(TestData.Admission(patientClass: "E"))), "CLM").Component(5, 1));
        Assert.Equal("11", TestData.Find(Set(TestData.Claim(TestData.Admission(patientClass: "O"))), "CLM").Component(5, 1));
    }

    [Fact]
    public void ASuppliedPlaceOfServiceWins()
    {
        X12TransactionSet set = Set(TestData.Claim(placeOfService: "22"));

        Assert.Equal("22", TestData.Find(set, "CLM").Component(5, 1));
    }

    [Fact]
    public void DemographicsAreSentWhenTheBirthDateIsComplete()
    {
        X12Segment demographics = TestData.Find(Set(), "DMG");

        Assert.Equal("D8", demographics.Value(1));
        Assert.Equal("19740314", demographics.Value(2));
        Assert.Equal("M", demographics.Value(3));
    }

    [Theory]
    [InlineData("1974-03")]
    [InlineData("1974")]
    [InlineData("")]
    public void DemographicsAreOmittedRatherThanInventedForAPartialBirthDate(string birthDate)
    {
        // The upstream service widens partial dates instead of padding them, precisely so that this
        // decision can be made here. Padding 1974-03 to 19740301 fabricates a birthday, and the
        // payer matches it against eligibility.
        X12TransactionSet set = Set(TestData.Claim(TestData.Admission(birthDate: birthDate)));

        Assert.False(TestData.Has(set, "DMG"));
    }

    [Fact]
    public void DemographicsAreOmittedWhenNoBirthDateWasSentAtAll()
    {
        X12TransactionSet set = Set(TestData.Claim(TestData.Admission(birthDate: null)));

        Assert.False(TestData.Has(set, "DMG"));
    }

    [Theory]
    [InlineData("M", "M")]
    [InlineData("F", "F")]
    [InlineData("f", "F")]
    [InlineData("A", "U")]  // HL7 "ambiguous" has no X12 equivalent
    [InlineData("O", "U")]  // HL7 "other"
    [InlineData("N", "U")]  // HL7 "not applicable"
    [InlineData("", "U")]
    public void HL7AdministrativeSexIsNarrowedToTheDmg03ValueSet(string hl7Value, string expected)
    {
        Assert.Equal(expected, Claim837PBuilder.MapAdministrativeSex(hl7Value));
    }

    [Fact]
    public void AnAbsentAdministrativeSexBecomesUnknown()
    {
        Assert.Equal("U", Claim837PBuilder.MapAdministrativeSex(null));
    }

    [Fact]
    public void TheIsaDateIsSixDigitsAndTheGsDateIsEight()
    {
        X12Interchange interchange = TestData.BuildClaimInterchange();

        // ISA09 was never widened to a four-digit year. This is the standard, not a bug.
        Assert.Equal("260825", interchange.Header.Date);
        Assert.Equal("20260825", interchange.Groups[0].Header.Date);
    }

    // ---- Mapping failures ----------------------------------------------------------------------

    [Fact]
    public void AMissingMedicalRecordNumberIsRejected()
    {
        X12MappingException error = Assert.Throws<X12MappingException>(
            () => TestData.Claim(TestData.Admission(mrn: null)));

        Assert.Equal(X12ErrorCode.MissingRequiredData, error.Code);
        Assert.Contains("medicalRecordNumber", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AMissingFamilyNameIsRejected()
    {
        X12MappingException error = Assert.Throws<X12MappingException>(
            () => TestData.Claim(TestData.Admission(familyName: null)));

        Assert.Contains("familyName", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ADiagnosisCodeWithADecimalPointIsRejected()
    {
        X12MappingException error = Assert.Throws<X12MappingException>(
            () => TestData.Claim(diagnosis: "J18.9"));

        Assert.Equal(X12ErrorCode.UnrepresentableValue, error.Code);
        Assert.Contains("decimal point", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AClaimWithNoServiceLinesIsRejected()
    {
        X12MappingException error = Assert.Throws<X12MappingException>(
            () => TestData.Claim(lines: new List<ServiceLineDocument>()));

        Assert.Contains("serviceLines", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AClaimIdLongerThanClm01IsRejectedRatherThanTruncated()
    {
        X12MappingException error = Assert.Throws<X12MappingException>(
            () => TestData.Claim(claimId: new string('A', ClaimRequest.MaxClaimIdLength + 1)));

        Assert.Equal(X12ErrorCode.UnrepresentableValue, error.Code);
        Assert.Contains("CLM01", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void MoreThanFourModifiersIsRejected()
    {
        var lines = new List<ServiceLineDocument>
        {
            new()
            {
                ProcedureCode = "99223",
                ChargeAmount = 100m,
                Units = 1m,
                Modifiers = new List<string> { "25", "59", "76", "77", "78" },
            },
        };

        Assert.Throws<X12MappingException>(() => TestData.Claim(lines: lines));
    }

    [Fact]
    public void APartialAdmissionDateIsRejectedBecauseTheClaimHasNoOtherSourceForTheServiceDate()
    {
        ClaimRequest claim = TestData.Claim(TestData.Admission(admitDateTime: "2026-08"));

        X12MappingException error = Assert.Throws<X12MappingException>(
            () => TestData.BuildClaimInterchange(claim));

        Assert.Equal(X12ErrorCode.UnrepresentableValue, error.Code);
        Assert.Contains("admitDateTime", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ANonPositiveChargeIsRejected()
    {
        var lines = new List<ServiceLineDocument>
        {
            new() { ProcedureCode = "99223", ChargeAmount = 0m, Units = 1m },
        };

        Assert.Throws<X12MappingException>(() => TestData.Claim(lines: lines));
    }
}
