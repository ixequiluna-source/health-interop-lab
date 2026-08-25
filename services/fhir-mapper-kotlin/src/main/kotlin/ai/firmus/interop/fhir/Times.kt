package ai.firmus.interop.fhir

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Date and time handling for the boundary between HL7-shaped strings, FHIR literals and BSON.
 *
 * Three different notions of "a time" meet here and none of them is the same:
 *
 *  - The upstream envelope carries whatever the sending system sent, widened but not invented:
 *    an offset when the sender gave one, none when it did not, and possibly a date with no
 *    time at all (`AdtMapper.normaliseTimestamp`).
 *  - FHIR `dateTime` forbids a time-of-day without a timezone offset. An offset-less string is
 *    not a FHIR dateTime, so it cannot be copied straight into the resource.
 *  - BSON has one date type, UTC milliseconds since the epoch. Anything stored has to be
 *    resolved to an instant, which for an offset-less value means choosing a zone.
 */

/** Matches a trailing `Z`, `+HH:MM` or `+HHMM`. */
private val OFFSET_SUFFIX = Regex("(Z|[+-]\\d{2}:?\\d{2})$")

private val LOCAL_DATE_TIME_OUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

private val YEAR_ONLY = Regex("\\d{4}")
private val YEAR_MONTH = Regex("\\d{4}-\\d{2}")
private val FULL_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

/**
 * Splits `value` into its local part and its offset, where the offset may be absent.
 *
 * Needed because a date-only value with an offset — `2026-08-25-05:00`, which the upstream
 * emits when the sender's DTM carried a zone but no time — cannot be handed to `LocalDate` or
 * to `OffsetDateTime`. The regex requires four digits after the sign, so the `-25` in
 * `2026-08-25` cannot be mistaken for an offset.
 */
internal fun splitOffset(value: String): Pair<String, String> {
    val match = OFFSET_SUFFIX.find(value) ?: return value to ""
    return value.substring(0, match.range.first) to match.value
}

private fun parseOffset(offset: String): ZoneOffset? =
    try {
        ZoneOffset.of(offset)
    } catch (_: DateTimeException) {
        null
    }

/**
 * Resolves an upstream timestamp to an instant, using [facilityZone] when the sender omitted
 * an offset.
 *
 * Truncated to milliseconds because that is BSON's resolution. Without the truncation an
 * `Instant` carrying microseconds compares as strictly greater than the value that comes back
 * out of Mongo, and the staleness guard — which compares an in-memory instant against a stored
 * BSON date — would judge an exact replay to be newer than itself.
 *
 * Returns null rather than a fallback for anything unparseable. A caller that needs a value
 * has to decide what to do about its absence explicitly; silently substituting "now" is how a
 * three-week-old backfill ends up looking like today's admissions.
 */
fun parseTimestamp(value: String, facilityZone: ZoneId): Instant? {
    if (value.isBlank()) return null
    val trimmed = value.trim()
    val (local, offset) = splitOffset(trimmed)

    val zone: ZoneId = if (offset.isEmpty()) facilityZone else parseOffset(offset) ?: return null

    return try {
        val instant = if (local.contains('T')) {
            LocalDateTime.parse(local).atZone(zone).toInstant()
        } else if (FULL_DATE.matches(local)) {
            // A date with no time is midnight local to the facility. Any choice here is an
            // assumption; midnight is the one that keeps the admission on the day the sender
            // said it happened.
            LocalDate.parse(local).atStartOfDay(zone).toInstant()
        } else {
            return null
        }
        instant.truncatedTo(ChronoUnit.MILLIS)
    } catch (_: DateTimeParseException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * Renders an upstream timestamp as a FHIR `dateTime`, or null if it cannot be one.
 *
 * FHIR's rule is that a dateTime with hours and minutes SHALL carry a timezone. The upstream
 * deliberately preserves the absence of an offset rather than assuming UTC, so this is where
 * the facility's zone gets applied — and applying it here, at the FHIR boundary, keeps the
 * assumption out of the canonical envelope where a different consumer might read it as fact.
 *
 * A date-only value stays a date. Adding a zone to it would be inventing a time of day, and
 * FHIR does not permit an offset without one anyway.
 */
fun toFhirDateTime(value: String, facilityZone: ZoneId): String? {
    if (value.isBlank()) return null
    val trimmed = value.trim()
    val (local, offset) = splitOffset(trimmed)

    if (!local.contains('T')) {
        return if (FULL_DATE.matches(local)) local else null
    }

    val parsed = try {
        LocalDateTime.parse(local)
    } catch (_: DateTimeParseException) {
        return null
    }

    val resolved = if (offset.isNotEmpty()) {
        parseOffset(offset) ?: return null
    } else {
        facilityZone.rules.getOffset(parsed)
    }
    // FHIR's grammar allows only whole-minute offsets. A historical zone with a sub-minute
    // offset would render as "+00:19:32" and fail validation downstream, so it is rejected
    // rather than emitted.
    if (resolved.totalSeconds % 60 != 0) return null

    return parsed.format(LOCAL_DATE_TIME_OUT) + resolved.id
}

/** Renders an instant as a FHIR `instant` literal, always UTC. */
fun toFhirInstant(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC).let {
    DateTimeFormatter.ISO_INSTANT.format(it)
}

/**
 * Validates a FHIR `date`, which has three legal precisions: `YYYY`, `YYYY-MM`, `YYYY-MM-DD`.
 *
 * The upstream widens partial HL7 dates rather than padding them, so all three arrive here.
 * Returning the string unchanged — never widening a `1974` into `1974-01-01` — is the whole
 * point: a padded birth date is a birthday the patient does not have, and paediatric dosing
 * downstream is computed from it.
 *
 * Returns null for anything that is not one of the three forms, including impossible dates
 * like `2026-02-30`, which reach here whenever a sending system does its own bad padding.
 */
fun normalisePartialDate(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    return try {
        when {
            YEAR_ONLY.matches(trimmed) -> {
                // Guard against year 0000, which parses but is not a date anyone was born on.
                if (trimmed.toInt() in 1..9999) trimmed else null
            }
            YEAR_MONTH.matches(trimmed) -> {
                YearMonth.parse(trimmed)
                trimmed
            }
            FULL_DATE.matches(trimmed) -> {
                // ISO_LOCAL_DATE resolves strictly, so 2026-02-30 throws rather than rolling
                // over into March.
                LocalDate.parse(trimmed)
                trimmed
            }
            else -> null
        }
    } catch (_: DateTimeParseException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}
