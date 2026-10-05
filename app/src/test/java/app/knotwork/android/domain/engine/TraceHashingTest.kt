package app.knotwork.android.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TraceHashing] is plain SHA-256 over UTF-8 — what a reader of an exported trace
 * recomputes with any tool.
 */
class TraceHashingTest {

    @Test
    fun `given the FIPS abc vector when hashed then the digest is the published one`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TraceHashing.sha256Hex("abc"),
        )
    }

    @Test
    fun `given an empty text when hashed then the digest is the empty-input SHA-256`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TraceHashing.sha256Hex(""),
        )
    }

    @Test
    fun `given non-ASCII text when hashed then its UTF-8 bytes are hashed`() {
        // "é" is the two bytes C3 A9 in UTF-8; a platform-charset encoding would hash others.
        assertEquals(
            "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c",
            TraceHashing.sha256Hex("é"),
        )
    }
}
