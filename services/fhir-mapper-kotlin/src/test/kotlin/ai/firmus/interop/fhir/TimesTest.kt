package ai.firmus.interop.fhir

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimesTest {

    private val mexicoCity = ZoneId.of("America/Mexico_City")
    private val utc = ZoneId.of("UTC")

    // --- partial dates -------------------------------------------------------------------

    /**
     * The upstream widens rather than pads, so all three precisions arrive. Widening them again
     * here — or padding them to a full date — invents a birthday, and paediatric dosing downstream
     * is computed from it.
     */
    @Test
    fun `keeps all three FHIR date precisions exactly as sent`() {
        assertEquals("1974", normalisePartialDate("1974"))
        assertEquals("1974-03", normalisePartialDate("1974-03"))
        assertEquals("1974-03-14", normalisePartialDate("1974-03-14"))
    }

    @Test
    fun `rejects dates that are well-formed and impossible`() {
        // A sending system that pads its own partial dates produces these, and a resolver in
        // lenient mode would roll 2026-02-30 forward into March without complaint.
        assertNull(normalisePartialDate("2026-02-30"))
        assertNull(normalisePartialDate("1974-13"))
        assertNull(normalisePartialDate("1974-00-14"))
        assertNull(normalisePartialDate("0000"))
    }

    @Test
    fun `rejects anything that is not one of the three forms`() {
        assertNull(normalisePartialDate(""))
        assertNull(normalisePartialDate("   "))
        assertNull(normalisePartialDate("19740314"))
        assertNull(normalisePartialDate("1974-3-4"))
        assertNull(normalisePartialDate("14/03/1974"))
        assertNull(normalisePartialDate("1974-03-14T00:00:00Z"))
    }

    // --- timestamps ----------------------------------------------------------------------

    @Test
    fun `honours the sender's offset when there is one`() {
        assertEquals(
            Instant.parse("2026-08-25T20:28:00Z"),
            parseTimestamp("2026-08-25T14:28:00-06:00", utc),
        )
    }

    @Test
    fun `applies the facility zone only when the sender omitted an offset`() {
        // The same wall-clock string resolves to different instants under different facility
        // zones, which is exactly why assuming UTC upstream would move overnight admissions onto
        // the wrong day.
        assertEquals(
            Instant.parse("2026-08-25T20:28:00Z"),
            parseTimestamp("2026-08-25T14:28:00", mexicoCity),
        )
        assertEquals(
            Instant.parse("2026-08-25T14:28:00Z"),
            parseTimestamp("2026-08-25T14:28:00", utc),
        )
    }

    @Test
    fun `reads a date-only timestamp as local midnight`() {
        assertEquals(Instant.parse("2026-08-25T06:00:00Z"), parseTimestamp("2026-08-25", mexicoCity))
    }

    @Test
    fun `separates a trailing offset from a date-only value`() {
        // The upstream emits this when the sender's DTM carried a zone but fewer than twelve
        // digits. A naive parse reads the "-25" of the date as the start of an offset.
        assertEquals(Instant.parse("2026-08-25T06:00:00Z"), parseTimestamp("2026-08-25-06:00", utc))
        assertEquals("2026-08-25" to "", splitOffset("2026-08-25"))
        assertEquals("2026-08-25" to "-06:00", splitOffset("2026-08-25-06:00"))
        assertEquals("2026-08-25T14:28:00" to "Z", splitOffset("2026-08-25T14:28:00Z"))
    }

    @Test
    fun `truncates to milliseconds so the staleness guard compares like with like`() {
        // BSON dates are milliseconds. An untruncated instant would read as strictly newer than
        // the value that comes back out of Mongo, and an exact replay would look like new data.
        val parsed = parseTimestamp("2026-08-25T14:28:00.123456789Z", utc)
        assertEquals(Instant.parse("2026-08-25T14:28:00.123Z"), parsed)
    }

    @Test
    fun `returns null rather than guessing`() {
        assertNull(parseTimestamp("", utc))
        assertNull(parseTimestamp("not a time", utc))
        assertNull(parseTimestamp("20260825142800", utc))
        assertNull(parseTimestamp("2026-08-25T14:28:00+99:00", utc))
    }

    // --- FHIR rendering ------------------------------------------------------------------

    @Test
    fun `attaches an offset to an offset-less dateTime because FHIR requires one`() {
        // FHIR: "If hours and minutes are specified, a timezone SHALL be populated."
        assertEquals("2026-08-25T14:28:00-06:00", toFhirDateTime("2026-08-25T14:28:00", mexicoCity))
        assertEquals("2026-08-25T14:28:00Z", toFhirDateTime("2026-08-25T14:28:00", utc))
    }

    @Test
    fun `preserves an offset the sender supplied`() {
        assertEquals(
            "2026-08-25T14:28:00-06:00",
            toFhirDateTime("2026-08-25T14:28:00-06:00", ZoneId.of("Europe/Madrid")),
        )
    }

    @Test
    fun `leaves a date-only value as a date`() {
        // Adding a zone would assert a time of day nobody recorded, and FHIR forbids an offset
        // without one anyway.
        assertEquals("2026-08-25", toFhirDateTime("2026-08-25", mexicoCity))
    }

    @Test
    fun `normalises a dateTime that arrived without seconds`() {
        assertEquals("2026-08-25T14:28:00Z", toFhirDateTime("2026-08-25T14:28", utc))
    }

    @Test
    fun `renders instants with second precision and a Z`() {
        assertEquals("2026-08-25T14:30:00Z", toFhirInstant(Instant.parse("2026-08-25T14:30:00Z")))
    }

    @Test
    fun `refuses to emit an unusable dateTime`() {
        assertNull(toFhirDateTime("", utc))
        assertNull(toFhirDateTime("2026-08", utc))
        assertNull(toFhirDateTime("yesterday", utc))
    }
}
