package ai.firmus.interop.fhir

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoggingTest {

    private val lines = mutableListOf<String>()

    private fun logger(
        level: LogLevel = LogLevel.INFO,
        includeExternalErrorMessages: Boolean = false,
    ) = Logger(
        component = "test",
        minLevel = level,
        includeExternalErrorMessages = includeExternalErrorMessages,
        clock = { Instant.parse("2026-08-25T14:30:00Z") },
        sink = { lines.add(it) },
    )

    private fun lastField(name: String): String? =
        Json.parseToJsonElement(lines.last()).jsonObject[name]?.jsonPrimitive?.content

    @Test
    fun `emits one JSON object per line with the standard envelope`() {
        logger().info("admission.applied", "eventId" to "evt-1", "attempt" to 2, "stale" to false)

        assertEquals(1, lines.size)
        val parsed = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("2026-08-25T14:30:00Z", parsed["ts"]?.jsonPrimitive?.content)
        assertEquals("INFO", parsed["level"]?.jsonPrimitive?.content)
        assertEquals("test", parsed["component"]?.jsonPrimitive?.content)
        assertEquals("admission.applied", parsed["event"]?.jsonPrimitive?.content)
        assertEquals("evt-1", parsed["eventId"]?.jsonPrimitive?.content)
        // Numbers and booleans stay typed rather than being stringified, so a log pipeline can
        // aggregate on them without parsing.
        assertEquals("2", parsed["attempt"]?.jsonPrimitive?.content)
        assertEquals("false", parsed["stale"]?.jsonPrimitive?.content)
    }

    @Test
    fun `escapes values that would otherwise break the line`() {
        // A field value containing a quote or a newline must not be able to forge a second log
        // record — the classic log-injection trick.
        logger().info("thing", "detail" to "a\"b\nc")

        assertEquals(1, lines.size)
        assertEquals("a\"b\nc", lastField("detail"))
    }

    @Test
    fun `respects the minimum level`() {
        val log = logger(level = LogLevel.WARN)

        log.debug("d")
        log.info("i")
        log.warn("w")
        log.error("e", null)

        assertEquals(listOf("w", "e"), lines.map { lastFieldOf(it, "event") })
    }

    // --- PHI ----------------------------------------------------------------------------------

    /**
     * Third-party exception messages are the leak nobody remembers. A JSON parser quotes the input
     * it failed on; a database driver quotes the document it rejected. Both are PHI here.
     */
    @Test
    fun `suppresses the message of an exception this service did not author`() {
        val log = logger()

        log.error("write.failed", IllegalStateException("failed on patient Núñez, MRN-88421"))

        assertNull(lastField("errorMessage"))
        assertEquals("java.lang.IllegalStateException", lastField("errorType"))
        assertFalse(lines.last().contains("Núñez"), lines.last())
        assertFalse(lines.last().contains("MRN-88421"), lines.last())
    }

    /**
     * The exceptions this service defines are written to name fields, never values, so their
     * messages are the diagnostic that makes a dead-letter actionable.
     */
    @Test
    fun `keeps the message of an exception this service authored`() {
        val log = logger()

        log.error("map.failed", MappingException("patient.medicalRecordNumber is blank"))

        assertEquals("patient.medicalRecordNumber is blank", lastField("errorMessage"))
    }

    @Test
    fun `can be opted out of for a non-PHI environment`() {
        val log = logger(includeExternalErrorMessages = true)

        log.error("write.failed", IllegalStateException("connection refused"))

        assertEquals("connection refused", lastField("errorMessage"))
    }

    @Test
    fun `records where an error came from even when the message is suppressed`() {
        val log = logger()

        log.error("write.failed", throwFromHere())

        // Without a message, the top frame in this service's own packages is what makes the error
        // findable in the code.
        assertTrue(lastField("errorAt")!!.contains("LoggingTest"), lastField("errorAt")!!)
    }

    @Test
    fun `safeMessage is the single place the rule lives`() {
        val strict = logger()
        val permissive = logger(includeExternalErrorMessages = true)

        assertNull(strict.safeMessage(RuntimeException("secret")))
        assertEquals("secret", permissive.safeMessage(RuntimeException("secret")))
        assertEquals("field is blank", strict.safeMessage(MappingException("field is blank")))
        assertEquals("bad zone", strict.safeMessage(ConfigException("bad zone")))
    }

    private fun throwFromHere(): Throwable = try {
        error("boom")
    } catch (e: IllegalStateException) {
        e
    }

    private fun lastFieldOf(line: String, name: String): String? =
        Json.parseToJsonElement(line).jsonObject[name]?.jsonPrimitive?.content
}
