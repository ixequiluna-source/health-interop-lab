package ai.firmus.interop.fhir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoldingTest {

    /**
     * The same cases as `TestFold` in `../patient-gateway-go/internal/patient`.
     *
     * These four are the contract between the two services. If this test and the Go one ever
     * disagree, patient search silently stops finding people whose names carry accents — the
     * gateway folds the query one way and this service folded the stored terms another.
     */
    @Test
    fun `folds the diacritics the Go gateway folds`() {
        assertEquals("nunez", fold("Núñez"))
        assertEquals("jose", fold("José"))
        assertEquals("muller", fold("Müller"))
        assertEquals("goncalves", fold("Gonçalves"))
    }

    @Test
    fun `lower-cases and trims like strings ToLower over TrimSpace`() {
        assertEquals("ixequi luna", fold("  Ixequi Luna  "))
        assertEquals("mcdonald", fold("MCDONALD"))
        assertEquals("", fold("   "))
        assertEquals("", fold(""))
    }

    @Test
    fun `leaves internal whitespace and punctuation alone`() {
        // Compound Hispanic surnames and hyphenated names must stay distinguishable; folding is
        // about accents and case, not about tokenising.
        assertEquals("nunez-luna", fold("Núñez-Luna"))
        assertEquals("de la o", fold("De La O"))
        assertEquals("o'brien", fold("O'Brien"))
    }

    @Test
    fun `folds every accented form in the shared table`() {
        assertEquals("aaaaaa", fold("áàäâãå"))
        assertEquals("eeee", fold("éèëê"))
        assertEquals("iiii", fold("íìïî"))
        assertEquals("ooooo", fold("óòöôõ"))
        assertEquals("uuuu", fold("úùüû"))
        assertEquals("ncyy", fold("ñçýÿ"))
    }

    @Test
    fun `passes through characters outside the table unchanged`() {
        // Deliberate: the table is the whole rule. A Cyrillic or Greek name is stored lower-cased
        // and otherwise untouched, and the Go side does exactly the same, so the two agree.
        assertEquals("иванов", fold("Иванов"))
        assertEquals("北京", fold("北京"))
    }

    @Test
    fun `search terms cover every way a clerk might start typing`() {
        val patient = AdmissionEvent.Patient(
            medicalRecordNumber = "MRN-88421",
            otherIdentifiers = listOf("IMSS-2211"),
            familyName = "Núñez Luna",
            givenName = "Ixequi",
        )

        val terms = buildSearchTerms(patient)

        // Whole names, individual parts, both joined orders, and the identifiers.
        assertTrue(terms.contains("nunez luna"), terms.toString())
        assertTrue(terms.contains("nunez"), terms.toString())
        assertTrue(terms.contains("luna"), terms.toString())
        assertTrue(terms.contains("ixequi"), terms.toString())
        assertTrue(terms.contains("ixequi nunez luna"), terms.toString())
        assertTrue(terms.contains("nunez luna ixequi"), terms.toString())
        assertTrue(terms.contains("mrn-88421"), terms.toString())
        assertTrue(terms.contains("imss-2211"), terms.toString())
    }

    @Test
    fun `search terms are sorted and de-duplicated so a replay writes identical bytes`() {
        val patient = AdmissionEvent.Patient(
            medicalRecordNumber = "MRN-1",
            familyName = "Luna",
            givenName = "Luna",
        )

        val terms = buildSearchTerms(patient)

        assertEquals(terms.sorted(), terms)
        assertEquals(terms.distinct(), terms)
        // "Luna" as both names collapses to one token plus the single joined form.
        assertEquals(listOf("luna", "luna luna", "mrn-1"), terms)
    }

    @Test
    fun `search terms skip blanks rather than emitting empty strings`() {
        // An empty term in the array would prefix-match every query, so every patient with a
        // missing name would come back in every search.
        val terms = buildSearchTerms(
            AdmissionEvent.Patient(medicalRecordNumber = "MRN-2", familyName = "", givenName = ""),
        )

        assertEquals(listOf("mrn-2"), terms)
    }
}
