package ai.firmus.interop.fhir

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FhirMapperTest {

    private val mapper = Fixtures.mapper()

    // --- gender, HL7 table 0001 to FHIR administrative-gender --------------------------------

    @Test
    fun `maps the whole HL7 administrative sex table`() {
        assertEquals("male", FhirMapper.mapGender("M"))
        assertEquals("female", FhirMapper.mapGender("F"))
        assertEquals("other", FhirMapper.mapGender("O"))
        // Ambiguous: a finding was recorded, it was simply neither male nor female.
        assertEquals("other", FhirMapper.mapGender("A"))
        // Not applicable: the concept does not apply to this record, which is weaker than "other".
        // A documented deviation from the R4 ConceptMap, which maps N onto other.
        assertEquals("unknown", FhirMapper.mapGender("N"))
        assertEquals("unknown", FhirMapper.mapGender("U"))
    }

    @Test
    fun `treats an unreadable sex code as unknown rather than guessing`() {
        assertEquals("unknown", FhirMapper.mapGender("X"))
        assertEquals("unknown", FhirMapper.mapGender("MALE"))
        assertEquals("unknown", FhirMapper.mapGender("9"))
    }

    @Test
    fun `is case and whitespace insensitive about the sex code`() {
        assertEquals("male", FhirMapper.mapGender("m"))
        assertEquals("female", FhirMapper.mapGender(" f "))
    }

    /**
     * Absent is not unknown. `gender: unknown` asserts that someone asked and did not find out;
     * omitting the element says the message did not carry it. One of those is a data-quality
     * report and the other is a clinical statement.
     */
    @Test
    fun `omits gender entirely when PID-8 was empty`() {
        assertNull(FhirMapper.mapGender(""))
        assertNull(FhirMapper.mapGender("   "))

        val resource = mapper.map(Fixtures.event(administrativeSex = "")).patient.fhir
        assertTrue("gender" !in resource.keys, resource.toString())
    }

    // --- encounter class, HL7 table 0004 to v3 ActCode ---------------------------------------

    @Test
    fun `maps patient class onto the right ActCode`() {
        assertEquals("IMP", FhirMapper.mapEncounterClass("I").code)
        assertEquals("AMB", FhirMapper.mapEncounterClass("O").code)
        assertEquals("EMER", FhirMapper.mapEncounterClass("E").code)
        assertEquals("PRENC", FhirMapper.mapEncounterClass("P").code)
        // Obstetrics behaves as inpatient, recurring as ambulatory; neither has its own ActCode.
        assertEquals("IMP", FhirMapper.mapEncounterClass("B").code)
        assertEquals("AMB", FhirMapper.mapEncounterClass("R").code)

        assertEquals(FhirSystems.V3_ACT_CODE, FhirMapper.mapEncounterClass("I").system)
    }

    /**
     * `Encounter.class` is 1..1 in R4, so a blank or unreadable PV1-2 cannot simply omit it. The
     * v3 null flavour is the modelled way to say "required, not known"; defaulting to AMB would
     * assert an outpatient visit nobody recorded.
     */
    @Test
    fun `uses the UNK null flavour when patient class says nothing usable`() {
        val unknown = FhirMapper.mapEncounterClass("")
        assertEquals("UNK", unknown.code)
        assertEquals(FhirSystems.V3_NULL_FLAVOR, unknown.system)
        assertEquals("UNK", FhirMapper.mapEncounterClass("Z").code)
    }

    // --- encounter status --------------------------------------------------------------------

    @Test
    fun `derives encounter status from the ADT trigger`() {
        assertEquals("in-progress", FhirMapper.mapEncounterStatus("A01"))
        assertEquals("in-progress", FhirMapper.mapEncounterStatus("A02"))
        assertEquals("in-progress", FhirMapper.mapEncounterStatus("A08"))
        assertEquals("finished", FhirMapper.mapEncounterStatus("A03"))
        assertEquals("cancelled", FhirMapper.mapEncounterStatus("A11"))
        // An unrecognised or missing trigger still describes an encounter that was open when the
        // message was sent.
        assertEquals("in-progress", FhirMapper.mapEncounterStatus(""))
    }

    // --- Patient resource --------------------------------------------------------------------

    @Test
    fun `builds a Patient resource with HL7 v2-0203 identifier types`() {
        val event = AdmissionEvent.parse(Fixtures.FULL_ADMIT)
        val resource = mapper.map(event).patient.fhir

        assertEquals("Patient", resource.string("resourceType"))
        assertEquals(ResourceIds.patient("MRN-88421"), resource.string("id"))

        val identifiers = resource["identifier"]!!.jsonArray
        assertEquals(3, identifiers.size)

        val mrn = identifiers[0].jsonObject
        assertEquals("usual", mrn.string("use"))
        assertEquals("MRN-88421", mrn.string("value"))
        assertEquals("urn:firmus:identifier:HGS", mrn.string("system"))
        val mrnCoding = mrn["type"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject
        assertEquals(FhirSystems.V2_0203_IDENTIFIER_TYPE, mrnCoding.string("system"))
        assertEquals("MR", mrnCoding.string("code"))

        val secondary = identifiers[1].jsonObject
        assertEquals("secondary", secondary.string("use"))
        assertEquals("IMSS-2211", secondary.string("value"))
        assertEquals(
            "PI",
            secondary["type"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject.string("code"),
        )
    }

    @Test
    fun `splits a compound given name into separate FHIR given entries`() {
        val resource = mapper.map(Fixtures.event(givenName = "María José", familyName = "Núñez")).patient.fhir

        val name = resource["name"]!!.jsonArray[0].jsonObject
        assertEquals("official", name.string("use"))
        assertEquals("Núñez", name.string("family"))
        assertEquals(listOf("María", "José"), name["given"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("María José Núñez", name.string("text"))
    }

    @Test
    fun `omits the name element entirely when the message carried no name`() {
        val resource = mapper.map(Fixtures.event(familyName = "", givenName = "")).patient.fhir

        assertTrue("name" !in resource.keys, resource.toString())
        // The identifier is still there — a patient with an MRN and no name is a real record.
        assertEquals("MRN-88421", resource["identifier"]!!.jsonArray[0].jsonObject.string("value"))
    }

    @Test
    fun `carries a family name with no given name`() {
        val resource = mapper.map(Fixtures.event(givenName = "")).patient.fhir

        val name = resource["name"]!!.jsonArray[0].jsonObject
        assertEquals("Núñez", name.string("family"))
        assertTrue("given" !in name.keys, name.toString())
        assertEquals("Núñez", name.string("text"))
    }

    @Test
    fun `keeps partial birth dates at the precision the sender used`() {
        assertEquals("1974", mapper.map(Fixtures.event(birthDate = "1974")).patient.fhir.string("birthDate"))
        assertEquals("1974-03", mapper.map(Fixtures.event(birthDate = "1974-03")).patient.fhir.string("birthDate"))
        assertEquals(
            "1974-03-14",
            mapper.map(Fixtures.event(birthDate = "1974-03-14")).patient.fhir.string("birthDate"),
        )
    }

    @Test
    fun `omits an absent or impossible birth date rather than inventing one`() {
        assertTrue("birthDate" !in mapper.map(Fixtures.event(birthDate = "")).patient.fhir.keys)
        assertTrue("birthDate" !in mapper.map(Fixtures.event(birthDate = "2026-02-30")).patient.fhir.keys)
        assertEquals("", mapper.map(Fixtures.event(birthDate = "2026-02-30")).patient.birthDate)
    }

    @Test
    fun `does not assert that a patient record is active`() {
        // An admission is evidence a record exists, not evidence about whether it is active — and
        // active:false stops downstream systems accepting charting against it.
        assertTrue("active" !in mapper.map(Fixtures.event()).patient.fhir.keys)
    }

    @Test
    fun `stamps provenance that is safe to publish`() {
        val meta = mapper.map(Fixtures.event(eventId = "evt-42")).patient.fhir["meta"]!!.jsonObject

        assertEquals("2026-08-25T14:30:00Z", meta.string("lastUpdated"))
        assertEquals("urn:firmus:hl7-ingest:event:evt-42", meta.string("source"))
    }

    // --- Encounter resource ------------------------------------------------------------------

    @Test
    fun `builds an Encounter that references the Patient it belongs to`() {
        val event = AdmissionEvent.parse(Fixtures.FULL_ADMIT)
        val mapped = mapper.map(event)
        val resource = mapped.encounter!!.fhir

        assertEquals("Encounter", resource.string("resourceType"))
        assertEquals("in-progress", resource.string("status"))
        assertEquals("IMP", resource["class"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject.string("code"))
        assertEquals("Patient/${mapped.patient.id}", resource["subject"]!!.jsonObject.string("reference"))
        assertEquals("Patient", resource["subject"]!!.jsonObject.string("type"))

        val visit = resource["identifier"]!!.jsonArray[0].jsonObject
        assertEquals("V-0099", visit.string("value"))
        assertEquals("VN", visit["type"]!!.jsonObject["coding"]!!.jsonArray[0].jsonObject.string("code"))

        assertEquals("2026-08-25T14:28:00-06:00", resource["period"]!!.jsonObject.string("start"))

        val participant = resource["participant"]!!.jsonArray[0].jsonObject
        assertEquals("Ana Ruiz", participant["individual"]!!.jsonObject.string("display"))
        assertEquals(
            "ATND",
            participant["type"]!!.jsonArray[0].jsonObject["coding"]!!.jsonArray[0].jsonObject.string("code"),
        )

        val location = resource["location"]!!.jsonArray[0].jsonObject
        assertEquals("HGS / 3N / 312 / A", location["location"]!!.jsonObject.string("display"))
        assertEquals("active", location.string("status"))
        assertEquals("HGS", resource["serviceProvider"]!!.jsonObject.string("display"))
    }

    @Test
    fun `omits period when the sender gave no admit time`() {
        // The read model still needs something to sort on, so it falls back to the event's own
        // recording time — but that approximation must not leak into the clinical resource.
        val mapped = mapper.map(Fixtures.event(admitDateTime = ""))

        assertTrue("period" !in mapped.encounter!!.fhir.keys)
        assertEquals(Instant.parse("2026-08-25T14:30:00Z"), mapped.encounter!!.admittedAt)
    }

    @Test
    fun `omits participant and location when the message carried neither`() {
        val resource = mapper.map(
            Fixtures.event(attendingClinician = "", pointOfCare = "", room = "", bed = "", facility = ""),
        ).encounter!!.fhir

        assertTrue("participant" !in resource.keys, resource.toString())
        assertTrue("location" !in resource.keys, resource.toString())
        assertTrue("serviceProvider" !in resource.keys, resource.toString())
    }

    @Test
    fun `builds a partial location display from whatever fields exist`() {
        val resource = mapper.map(Fixtures.event(room = "", bed = "")).encounter!!.fhir

        assertEquals("HGS / 3N", resource["location"]!!.jsonArray[0].jsonObject["location"]!!.jsonObject.string("display"))
    }

    @Test
    fun `produces no encounter for a person-information message`() {
        val mapped = mapper.map(AdmissionEvent.parse(Fixtures.PERSON_INFO_ONLY))

        assertNull(mapped.encounter)
        // The patient half still lands: A28 exists precisely to update demographics.
        assertEquals("MRN-3001", mapped.patient.medicalRecordNumber)
        assertEquals("goncalves", mapped.patient.foldedFamilyName)
    }

    @Test
    fun `marks a discharge encounter finished`() {
        val resource = mapper.map(Fixtures.event(messageType = "ADT^A03")).encounter!!.fhir

        assertEquals("finished", resource.string("status"))
    }

    // --- deterministic identity --------------------------------------------------------------

    @Test
    fun `derives the same ids from the same event every time`() {
        val first = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT))
        val second = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT))

        assertEquals(first.patient.id, second.patient.id)
        assertEquals(first.encounter!!.id, second.encounter!!.id)
        // 64 hex characters: exactly the FHIR id limit, and only characters its grammar allows.
        assertTrue(Regex("[a-f0-9]{64}").matches(first.patient.id), first.patient.id)
        assertTrue(Regex("[A-Za-z0-9.-]{1,64}").matches(first.encounter!!.id))
    }

    @Test
    fun `keys the encounter on the visit number so an update converges on the admit`() {
        // A01 admit, then A08 update: two events, two event ids, one visit — and therefore one
        // document, which is what stops a correction appearing as a second admission.
        val admit = mapper.map(Fixtures.event(eventId = "evt-a01", messageType = "ADT^A01"))
        val update = mapper.map(Fixtures.event(eventId = "evt-a08", messageType = "ADT^A08"))

        assertEquals(admit.encounter!!.id, update.encounter!!.id)
        assertNotEquals(admit.eventId, update.eventId)
    }

    @Test
    fun `separates different visits and different patients`() {
        val visitOne = mapper.map(Fixtures.event(visitNumber = "V-1"))
        val visitTwo = mapper.map(Fixtures.event(visitNumber = "V-2"))
        val otherPatient = mapper.map(Fixtures.event(medicalRecordNumber = "MRN-2", visitNumber = "V-1"))

        assertNotEquals(visitOne.encounter!!.id, visitTwo.encounter!!.id)
        assertNotEquals(visitOne.encounter!!.id, otherPatient.encounter!!.id)
        assertNotEquals(visitOne.patient.id, otherPatient.patient.id)
    }

    @Test
    fun `falls back to the event id when there is no visit number`() {
        // Weaker — two messages about the same visit produce two documents — but still idempotent
        // under replay, which is the property that protects the read model. Keying on the admit
        // timestamp instead would merge two genuinely different visits that began in one second.
        val first = mapper.map(Fixtures.event(eventId = "evt-1", visitNumber = ""))
        val replay = mapper.map(Fixtures.event(eventId = "evt-1", visitNumber = ""))
        val other = mapper.map(Fixtures.event(eventId = "evt-2", visitNumber = ""))

        assertEquals(first.encounter!!.id, replay.encounter!!.id)
        assertNotEquals(first.encounter!!.id, other.encounter!!.id)
        assertTrue("identifier" !in first.encounter!!.fhir.keys)
    }

    // --- identifier systems ------------------------------------------------------------------

    @Test
    fun `derives the FHIR identifier system from the sending facility`() {
        assertEquals("urn:firmus:identifier:HGS", mapper.identifierSystemUri("HGS"))
        // A facility name with spaces or slashes is not a URI; the read model keeps the raw name.
        assertEquals("urn:firmus:identifier:HOSPITAL-GENERAL-SUR", mapper.identifierSystemUri("HOSPITAL GENERAL/SUR"))
    }

    @Test
    fun `falls back to the configured authority when MSH-4 was empty`() {
        val mapped = mapper.map(Fixtures.event(sendingFacility = ""))

        assertEquals("UNKNOWN", mapped.patient.identifiers[0].system)
        assertEquals("urn:firmus:identifier:UNKNOWN", mapped.patient.fhir["identifier"]!!.jsonArray[0].jsonObject.string("system"))
    }

    // --- failure -----------------------------------------------------------------------------

    @Test
    fun `refuses to map an event whose recording time cannot be parsed`() {
        // recordedAt drives both the staleness guard and meta.lastUpdated. Substituting "now"
        // would make a replayed backfill look like today's traffic and defeat the guard.
        val error = assertFailsWith<MappingException> {
            mapper.map(Fixtures.event(recordedAt = "25/08/2026"))
        }
        assertTrue(error.message!!.contains("recordedAt"))
    }

    @Test
    fun `resolves an offset-less admit time in the facility zone`() {
        val utcMapper = Fixtures.mapper(ZoneId.of("UTC"))

        val local = mapper.map(Fixtures.event(admitDateTime = "2026-08-25T14:28:00"))
        val utc = utcMapper.map(Fixtures.event(admitDateTime = "2026-08-25T14:28:00"))

        assertEquals(Instant.parse("2026-08-25T20:28:00Z"), local.encounter!!.admittedAt)
        assertEquals(Instant.parse("2026-08-25T14:28:00Z"), utc.encounter!!.admittedAt)
        assertEquals("2026-08-25T14:28:00-06:00", local.encounter!!.fhir["period"]!!.jsonObject.string("start"))
        assertEquals("2026-08-25T14:28:00Z", utc.encounter!!.fhir["period"]!!.jsonObject.string("start"))
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
