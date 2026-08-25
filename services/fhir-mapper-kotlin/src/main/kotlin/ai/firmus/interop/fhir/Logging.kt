package ai.firmus.interop.fhir

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * A structured logger that cannot casually emit PHI.
 *
 * ## Why this exists rather than an SLF4J facade over Logback
 *
 * The constraint is not formatting, it is content. HL7 payloads and every field derived from
 * them are protected health information, and application logs almost never live under the same
 * retention, encryption and access-review regime as the clinical store — which is precisely the
 * gap HIPAA's minimum-necessary rule and SOC 2 CC6.1 are written about. A logger whose API
 * takes a free-form message string invites `log.info("mapped ${'$'}event")`, and that one line
 * ships names and MRNs into a search index that half the company can read.
 *
 * So the API takes an *event name* and typed key/value fields, and the call sites in this
 * service pass only identifiers that are meaningless without the clinical store: event ids,
 * HL7 message control ids, topic/partition/offset coordinates, durations and counts. Never a
 * name, never an MRN, never a message body.
 *
 * ## Exception messages
 *
 * Third-party exception messages are the other leak. A driver-level error can quote the
 * document it failed on, and a JSON parser can quote the input around the failure. Only
 * exceptions this service defines — whose messages are written to name fields rather than
 * values — have their message logged by default. Everything else contributes its type and the
 * top stack frame, which is what you actually need to find the code. `LOG_EXTERNAL_ERROR_MESSAGES`
 * turns the rest back on for a non-PHI environment.
 */
class Logger(
    private val component: String,
    private val minLevel: LogLevel = LogLevel.INFO,
    private val includeExternalErrorMessages: Boolean = false,
    private val clock: () -> Instant = Instant::now,
    private val sink: (String) -> Unit = { line -> println(line) },
) {
    fun withComponent(name: String): Logger =
        Logger(name, minLevel, includeExternalErrorMessages, clock, sink)

    fun debug(event: String, vararg fields: Pair<String, Any?>) = emit(LogLevel.DEBUG, event, fields, null)

    fun info(event: String, vararg fields: Pair<String, Any?>) = emit(LogLevel.INFO, event, fields, null)

    fun warn(event: String, vararg fields: Pair<String, Any?>) = emit(LogLevel.WARN, event, fields, null)

    fun warn(event: String, error: Throwable, vararg fields: Pair<String, Any?>) =
        emit(LogLevel.WARN, event, fields, error)

    fun error(event: String, error: Throwable?, vararg fields: Pair<String, Any?>) =
        emit(LogLevel.ERROR, event, fields, error)

    private fun emit(
        level: LogLevel,
        event: String,
        fields: Array<out Pair<String, Any?>>,
        error: Throwable?,
    ) {
        if (level.ordinal < minLevel.ordinal) return

        val line = buildJsonObject {
            put("ts", clock().toString())
            put("level", level.name)
            put("component", component)
            put("event", event)
            for ((key, value) in fields) {
                put(key, encode(value))
            }
            if (error != null) {
                put("errorType", error.javaClass.name)
                safeMessage(error)?.let { put("errorMessage", it) }
                originOf(error)?.let { put("errorAt", it) }
            }
        }.toString()

        sink(line)
    }

    /**
     * Returns an exception's message only when this service authored it.
     *
     * Visible for testing: the rule is easier to trust when it can be asserted directly.
     */
    internal fun safeMessage(error: Throwable): String? = when {
        error is MappingException || error is ConfigException -> error.message
        includeExternalErrorMessages -> error.message
        else -> null
    }

    private fun originOf(error: Throwable): String? {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("ai.firmus.") }
            ?: error.stackTrace.firstOrNull()
            ?: return null
        return "${frame.className}.${frame.methodName}:${frame.lineNumber}"
    }

    private fun encode(value: Any?) = when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
