package ai.firmus.interop.fhir

import org.bson.Document
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectionTest {

    private val mapper = Fixtures.mapper()

    /**
     * These field names are a wire contract with `mongostore.patientDoc` in the Go gateway, which
     * decodes by explicit `bson:` tag. A rename here is not a refactor — the Go driver decodes a
     * missing field to a zero value rather than failing, so the gateway would return blank patients
     * and report no error at all.
     */
    @Test
    fun `patient document carries exactly the fields the Go gateway decodes`() {
        val document = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT)).patient.toDocument()

        assertEquals("MRN-88421", document.getString("medicalRecordNumber"))
        assertEquals("Núñez Luna", document.getString("familyName"))
        assertEquals("Ixequi", document.getString("givenName"))
        assertEquals("1974-03-14", document.getString("birthDate"))
        assertEquals("M", document.getString("administrativeSex"))
        assertEquals("nunez luna", document.getString("foldedFamilyName"))
        assertEquals("ixequi", document.getString("foldedGivenName"))
        assertEquals(Date.from(Instant.parse("2026-08-25T14:30:00Z")), document["lastUpdated"])

        // Present so the gateway's ^-anchored $regex over an array can use the search_terms index.
        assertIs<List<*>>(document["searchTerms"])
        assertTrue((document["searchTerms"] as List<*>).contains("nunez luna"))
    }

    @Test
    fun `identifiers are documents with system, value and type`() {
        val document = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT)).patient.toDocument()

        @Suppress("UNCHECKED_CAST")
        val identifiers = document["identifiers"] as List<Document>
        assertEquals(3, identifiers.size)

        // The read model's `system` is the bare assigning authority the proto documents ("e.g. HGS
        // or IMSS"), not the URI the FHIR resource uses. The two are different vocabularies and
        // conflating them would break one side or the other.
        assertEquals("HGS", identifiers[0].getString("system"))
        assertEquals("MRN-88421", identifiers[0].getString("value"))
        assertEquals("MR", identifiers[0].getString("type"))
        assertEquals("PI", identifiers[1].getString("type"))
        assertEquals("IMSS-2211", identifiers[1].getString("value"))
    }

    @Test
    fun `encounter document carries exactly the fields the Go gateway decodes`() {
        val document = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT)).encounter!!.toDocument()

        assertEquals("V-0099", document.getString("visitNumber"))
        assertEquals("MRN-88421", document.getString("medicalRecordNumber"))
        assertEquals("I", document.getString("patientClass"))
        assertEquals(Date.from(Instant.parse("2026-08-25T20:28:00Z")), document["admittedAt"])
        assertEquals("Ana Ruiz", document.getString("attendingClinician"))
        assertEquals("3N", document.getString("pointOfCare"))
        assertEquals("312", document.getString("room"))
        assertEquals("A", document.getString("bed"))
        assertEquals("HGS", document.getString("facility"))
    }

    @Test
    fun `the document id is the deterministic resource id`() {
        // This is what makes a replay an upsert onto the row already there instead of a second row.
        val mapped = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT))

        assertEquals(ResourceIds.patient("MRN-88421"), mapped.patient.toDocument().getString("_id"))
        assertEquals(
            ResourceIds.encounter("MRN-88421", "V-0099", "ignored"),
            mapped.encounter!!.toDocument().getString("_id"),
        )
    }

    @Test
    fun `two mappings of the same event produce identical documents`() {
        // Byte-identical output under replay is what stops every redelivery looking like a change
        // to anything diffing the collection — a change stream, an audit trail, a CDC pipeline.
        val first = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT))
        val second = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT))

        assertEquals(first.patient.toDocument(), second.patient.toDocument())
        assertEquals(first.encounter!!.toDocument(), second.encounter!!.toDocument())
    }

    // --- the embedded FHIR resource ----------------------------------------------------------

    @Test
    fun `the FHIR resource is stored as a nested document, not a JSON string`() {
        // Nested so `db.patients.find({"fhir.gender": "other"})` is a data-quality report rather
        // than a regular expression over the whole collection.
        val document = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT)).patient.toDocument()

        val fhir = assertIs<Document>(document["fhir"])
        assertEquals("Patient", fhir.getString("resourceType"))
        assertEquals("male", fhir.getString("gender"))

        @Suppress("UNCHECKED_CAST")
        val identifiers = fhir["identifier"] as List<Document>
        assertEquals("urn:firmus:identifier:HGS", identifiers[0].getString("system"))
        assertEquals(
            "MR",
            (identifiers[0]["type"] as Document).let { it["coding"] as List<*> }
                .let { (it[0] as Document).getString("code") },
        )
    }

    @Test
    fun `identifier values that look like numbers stay strings`() {
        // A JSON primitive knows whether it was quoted, so the conversion does not have to guess
        // from the characters. Storing an MRN as a BSON int would make the gateway's prefix search
        // miss it entirely.
        val document = mapper.map(Fixtures.event(medicalRecordNumber = "0012345")).patient.toDocument()

        val fhir = assertIs<Document>(document["fhir"])
        @Suppress("UNCHECKED_CAST")
        val identifiers = fhir["identifier"] as List<Document>
        assertIs<String>(identifiers[0]["value"])
        assertEquals("0012345", identifiers[0].getString("value"))
    }

    @Test
    fun `given names round-trip through the array conversion`() {
        val document = mapper.map(Fixtures.event(givenName = "María José")).patient.toDocument()

        val fhir = assertIs<Document>(document["fhir"])
        @Suppress("UNCHECKED_CAST")
        val names = fhir["name"] as List<Document>
        assertContentEquals(listOf<Any?>("María", "José"), names[0]["given"] as List<Any?>)
    }

    // --- PHI ---------------------------------------------------------------------------------

    /**
     * The generated `toString` of a data class renders every property. These types hold a name, an
     * MRN, a birth date and a visit number, so the generated one is a disclosure waiting for a
     * careless log call or an exception message that interpolates the object.
     */
    @Test
    fun `projection toString does not disclose PHI`() {
        val mapped = mapper.map(AdmissionEvent.parse(Fixtures.FULL_ADMIT))

        val rendered = "${mapped.patient}|${mapped.encounter}|${mapped.patient.identifiers[0]}"

        assertFalse(rendered.contains("Núñez"), rendered)
        assertFalse(rendered.contains("Ixequi"), rendered)
        assertFalse(rendered.contains("MRN-88421"), rendered)
        assertFalse(rendered.contains("V-0099"), rendered)
        assertFalse(rendered.contains("1974"), rendered)
        assertFalse(rendered.contains("Ana Ruiz"), rendered)
        // Still useful: the opaque resource id and the timestamps are safe to correlate on.
        assertTrue(rendered.contains(mapped.patient.id), rendered)
    }
}
