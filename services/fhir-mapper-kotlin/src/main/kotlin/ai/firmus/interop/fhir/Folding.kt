package ai.firmus.interop.fhir

/**
 * Name folding, kept byte-for-byte compatible with the Go read gateway.
 *
 * The gateway searches by matching a folded query term against the pre-folded `searchTerms`
 * array this service writes (`internal/mongostore/mongo.go`). Both sides therefore have to
 * agree on exactly what folding means. They are two implementations of one rule in two
 * languages, which is a duplication we accept and defend with tests on both sides — the
 * alternative is folding at query time with a `$regex` over raw names, which cannot use an
 * index and turns every search into a collection scan.
 *
 * See `../patient-gateway-go/internal/patient/fold.go`; the table below is a transcription of
 * the one there and must be changed in lockstep.
 */

/**
 * The accented Latin letters that occur in Spanish, Portuguese, French and German name data,
 * mapped onto their unaccented forms.
 *
 * Written out rather than derived from Unicode NFD + combining-mark stripping. An explicit
 * table is auditable: a reviewer can see that ñ folds to n deliberately, and can see that the
 * Go and Kotlin tables are the same table. NFD stripping would also fold characters the Go
 * side does not — Greek, Cyrillic, Vietnamese tone marks — and the two indexes would silently
 * diverge for exactly the patients nobody tests with.
 */
private val DIACRITICS: Map<Char, Char> = mapOf(
    'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'ã' to 'a', 'å' to 'a',
    'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
    'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o', 'õ' to 'o',
    'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
    'ñ' to 'n', 'ç' to 'c', 'ý' to 'y', 'ÿ' to 'y',
)

/**
 * Normalises a string for comparison: trimmed, lower-cased, diacritics removed.
 *
 * Folding ñ to n is not linguistically neutral — in Spanish orthography ñ and n are distinct
 * letters. It is done anyway because patient names arrive from one system with accents and
 * from another without, and a search that treats "Nunez" and "Núñez" as different people
 * reports "no such patient" for someone who is currently admitted. Over-recall is the safer
 * failure; the clinician still sees both records and decides.
 *
 * `lowercase()` is used without a Locale on purpose: the Locale-sensitive overload lower-cases
 * 'I' to 'ı' under a Turkish default locale, so the folded index would depend on the JVM's
 * environment and would stop matching what the Go gateway wrote.
 */
fun fold(value: String): String {
    val normalised = value.trim().lowercase()
    val out = StringBuilder(normalised.length)
    for (ch in normalised) {
        // Iteration is over UTF-16 code units rather than code points. Every character in the
        // table is in the BMP, so a surrogate pair is copied through unchanged as its two
        // units — the same result the Go rune loop produces.
        out.append(DIACRITICS[ch] ?: ch)
    }
    return out.toString()
}

private val WHITESPACE = Regex("\\s+")

/**
 * Builds the pre-folded token array the gateway prefix-matches against.
 *
 * The gateway's filter is `searchTerms: { $regex: "^" + fold(term) }` over an array field, so
 * a term matches when *any* element starts with it. That shapes what belongs in the array:
 *
 *  - each name as a whole, so "Nun" finds "Núñez";
 *  - each whitespace-separated part, so "Luna" finds a compound surname "Núñez Luna";
 *  - both name orders joined, so "Ixequi Nu" — how a clerk actually types — still prefixes a
 *    single element rather than needing two;
 *  - the MRN and every secondary identifier, because staff search by number far more often
 *    than by name.
 *
 * Sorted and de-duplicated so the document is a deterministic function of the event: two
 * replays of the same event must produce byte-identical documents, or every replay looks like
 * a change to anything diffing the collection.
 */
fun buildSearchTerms(patient: AdmissionEvent.Patient): List<String> {
    val terms = LinkedHashSet<String>()

    fun add(raw: String) {
        val folded = fold(raw)
        if (folded.isNotEmpty()) terms.add(folded)
    }

    add(patient.familyName)
    add(patient.givenName)
    patient.familyName.split(WHITESPACE).forEach { add(it) }
    patient.givenName.split(WHITESPACE).forEach { add(it) }
    add("${patient.givenName} ${patient.familyName}")
    add("${patient.familyName} ${patient.givenName}")
    add(patient.medicalRecordNumber)
    patient.otherIdentifiers.forEach { add(it) }

    return terms.sorted()
}
