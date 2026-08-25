package ai.firmus.interop.fhir

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdmissionEventTest {

    @Test
    fun `parses the envelope the Java ingest service emits`() {
        val event = AdmissionEvent.parse(Fixtures.FULL_ADMIT)

        assertEquals("1.0.0", event.schemaVersion)
        assertEquals("6f1c0f2e-2c3a-4f6b-9a1d-0d1f2a3b4c5d", event.eventId)
        assertEquals("MSG00001", event.messageControlId)
        assertEquals("ADT^A01", event.messageType)
        assertEquals("HGS", event.sendingFacility)
        assertEquals("2026-08-25T14:30:00Z", event.recordedAt)

        assertEquals("MRN-88421", event.patient.medicalRecordNumber)
        assertEquals(listOf("IMSS-2211", "SSA-77"), event.patient.otherIdentifiers)
        assertEquals("Núñez Luna", event.patient.familyName)
        assertEquals("1974-03-14", event.patient.birthDate)

        assertEquals("V-0099", event.encounter.visitNumber)
        assertEquals("2026-08-25T14:28:00-06:00", event.encounter.admitDateTime)
        assertEquals("A", event.encounter.bed)
    }

    /**
     * The producer omits empty fields rather than sending null, so absence is the normal case for
     * every optional key — including `otherIdentifiers`, which is dropped entirely when empty
     * rather than sent as `[]`. Defaults have to cover all of it or the common message fails.
     */
    @Test
    fun `treats every omitted optional field as empty`() {
        val event = AdmissionEvent.parse(Fixtures.MINIMAL_ADMIT)

        assertEquals("MRN-1", event.patient.medicalRecordNumber)
        assertEquals("", event.patient.familyName)
        assertEquals("", event.patient.givenName)
        assertEquals("", event.patient.birthDate)
        assertEquals("", event.patient.administrativeSex)
        assertEquals(emptyList(), event.patient.otherIdentifiers)

        assertEquals("", event.messageType)
        assertEquals("", event.sendingFacility)
        assertTrue(event.encounter.isEmpty())
    }

    @Test
    fun `accepts an explicit null in place of an omitted field`() {
        // The Java producer never does this, but a future producer in another language might, and
        // rejecting the whole message over it would dead-letter a perfectly usable admission.
        val payload = """
            {"schemaVersion":"1.0.0","eventId":"e1","recordedAt":"2026-08-25T14:30:00Z",
            "patient":{"medicalRecordNumber":"MRN-1","givenName":null},"encounter":{}}
        """

        assertEquals("", AdmissionEvent.parse(payload).patient.givenName)
    }

    @Test
    fun `ignores fields a newer producer added`() {
        // Additive schema change must not require deploying every consumer first.
        val payload = """
            {"schemaVersion":"1.0.0","eventId":"e1","recordedAt":"2026-08-25T14:30:00Z",
            "priorPatientIdentifier":"MRN-0","patient":{"medicalRecordNumber":"MRN-1",
            "deathIndicator":"N"},"encounter":{}}
        """

        assertEquals("MRN-1", AdmissionEvent.parse(payload).patient.medicalRecordNumber)
    }

    @Test
    fun `rejects a schema major version it was not written for`() {
        val payload = """
            {"schemaVersion":"2.0.0","eventId":"e1","recordedAt":"2026-08-25T14:30:00Z",
            "patient":{"medicalRecordNumber":"MRN-1"},"encounter":{}}
        """

        val error = assertFailsWith<MappingException> { AdmissionEvent.parse(payload) }
        assertTrue(error.message!!.contains("schemaVersion"))
    }

    @Test
    fun `rejects an event with no medical record number`() {
        val payload = """
            {"schemaVersion":"1.0.0","eventId":"e1","recordedAt":"2026-08-25T14:30:00Z",
            "patient":{},"encounter":{}}
        """

        val error = assertFailsWith<MappingException> { AdmissionEvent.parse(payload) }
        assertEquals("patient.medicalRecordNumber is blank", error.message)
    }

    @Test
    fun `rejects an event with no id or no recording time`() {
        assertFailsWith<MappingException> {
            AdmissionEvent.parse(
                """{"schemaVersion":"1.0.0","eventId":"","recordedAt":"2026-08-25T14:30:00Z",
                   "patient":{"medicalRecordNumber":"MRN-1"},"encounter":{}}""",
            )
        }
        assertFailsWith<MappingException> {
            AdmissionEvent.parse(
                """{"schemaVersion":"1.0.0","eventId":"e1","recordedAt":"",
                   "patient":{"medicalRecordNumber":"MRN-1"},"encounter":{}}""",
            )
        }
    }

    @Test
    fun `raises a serialization error, not a mapping error, for malformed JSON`() {
        // The distinction matters downstream: both are dead-lettered, but only the mapping error's
        // message is safe to log, because this service wrote it.
        assertFailsWith<SerializationException> { AdmissionEvent.parse("{not json") }
        assertFailsWith<SerializationException> { AdmissionEvent.parse("[]") }
    }

    @Test
    fun `extracts the ADT trigger from the message type`() {
        assertEquals("A01", Fixtures.event(messageType = "ADT^A01").triggerEvent())
        assertEquals("A08", Fixtures.event(messageType = "ADT^A08^ADT_A08").triggerEvent())
        assertEquals("", Fixtures.event(messageType = "").triggerEvent())
        assertEquals("", Fixtures.event(messageType = "ADT").triggerEvent())
    }

    @Test
    fun `recognises a person-information message as having no visit`() {
        val event = AdmissionEvent.parse(Fixtures.PERSON_INFO_ONLY)

        assertEquals("A28", event.triggerEvent())
        assertTrue(event.encounter.isEmpty())
    }

    @Test
    fun `treats an encounter with any single populated field as a visit`() {
        // A PV1 that carries only a point of care is thin, but it is still a visit; only a
        // completely empty one means "this message was not about an encounter".
        assertFalse(AdmissionEvent.Encounter(pointOfCare = "3N").isEmpty())
        assertFalse(AdmissionEvent.Encounter(visitNumber = "V-1").isEmpty())
        assertTrue(AdmissionEvent.Encounter().isEmpty())
    }

    /**
     * The generated `toString` of a data class renders every property. One `"event" to event` in a
     * log call would then publish a name, an MRN and a birth date into the log pipeline, which
     * does not live under the clinical store's retention and access controls.
     */
    @Test
    fun `toString does not disclose PHI`() {
        val event = Fixtures.event(familyName = "Núñez", givenName = "Ixequi", medicalRecordNumber = "MRN-88421")

        val rendered = "${event}|${event.patient}|${event.encounter}"

        assertFalse(rendered.contains("Núñez"), rendered)
        assertFalse(rendered.contains("Ixequi"), rendered)
        assertFalse(rendered.contains("MRN-88421"), rendered)
        assertFalse(rendered.contains("1974"), rendered)
        assertFalse(rendered.contains("Ana Ruiz"), rendered)
        // The safe identifiers are still there, because a log line has to be usable.
        assertTrue(rendered.contains("evt-1"), rendered)
        assertTrue(rendered.contains("MSG00001"), rendered)
    }
}
