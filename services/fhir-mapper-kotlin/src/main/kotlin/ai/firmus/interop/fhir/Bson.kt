package ai.firmus.interop.fhir

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bson.Document

/**
 * Converts a FHIR resource from its JSON model into a BSON document.
 *
 * Storing the resource as a nested document rather than as a JSON string is what makes it
 * queryable — `db.patients.find({"fhir.gender": "other"})` is a data-quality report, and against
 * a string column it is a regular expression over the whole collection. FHIR element names are
 * safe as BSON keys: the specification's own grammar excludes `$` and `.`, which are the two
 * characters MongoDB restricts.
 *
 * The `JsonNull` case is checked before `JsonPrimitive` because `JsonNull` is a subtype of it;
 * reversing the two branches turns every null into the four-character string "null". In practice
 * the mapper never emits a null — an element it cannot fill is omitted — but a conversion that
 * silently corrupts a value it was never meant to see is not worth the two saved lines.
 */
fun JsonObject.toBsonDocument(): Document {
    val document = Document()
    for ((key, value) in this) {
        document.append(key, value.toBsonValue())
    }
    return document
}

private fun JsonElement.toBsonValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> primitiveToBson(this)
    is JsonObject -> toBsonDocument()
    is JsonArray -> map { it.toBsonValue() }
}

/**
 * A JSON primitive carries its own "was this quoted" flag, so the distinction between the
 * string `"3"` and the number `3` survives the conversion instead of being guessed from the
 * characters. That matters here: FHIR identifier values and MRNs are strings that frequently
 * look like integers, and storing one as a BSON int makes the gateway's `$regex` prefix search
 * silently miss it.
 */
private fun primitiveToBson(primitive: JsonPrimitive): Any = when {
    primitive.isString -> primitive.content
    else -> primitive.content.toBooleanStrictOrNull()
        ?: primitive.content.toLongOrNull()
        ?: primitive.content.toDoubleOrNull()
        ?: primitive.content
}
