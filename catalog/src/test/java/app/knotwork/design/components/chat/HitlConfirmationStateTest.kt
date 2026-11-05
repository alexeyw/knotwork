package app.knotwork.design.components.chat

import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.dialogs.typedConfirmMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [HitlConfirmationState] — the gating logic behind
 * [HitlConfirmationCard]'s Allow CTA, Always-Allow visibility, and
 * destructive typed-confirm row.
 *
 * Keeps the rules unit-testable without Robolectric / Compose so the
 * behaviour survives refactors of the Composable itself.
 */
class HitlConfirmationStateTest {

    @Test
    fun `given readonly when isAllowOnceEnabled then returns true regardless of typed value`() {
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Readonly, "", WORD))
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Readonly, "no", WORD))
    }

    @Test
    fun `given sensitive when isAllowOnceEnabled then returns true regardless of typed value`() {
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Sensitive, "", WORD))
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Sensitive, "anything", WORD))
    }

    @Test
    fun `given destructive and empty typed when isAllowOnceEnabled then returns false`() {
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "", WORD))
    }

    @Test
    fun `given destructive and partial typed when isAllowOnceEnabled then returns false`() {
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "ye", WORD))
    }

    @Test
    fun `given destructive and exact match when isAllowOnceEnabled then returns true`() {
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "yes", WORD))
    }

    @Test
    fun `given destructive and uppercase match when isAllowOnceEnabled then returns true`() {
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "YES", WORD))
    }

    @Test
    fun `given destructive and padded match when isAllowOnceEnabled then trims and returns true`() {
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "  yes  ", WORD))
    }

    @Test
    fun `given destructive and non-matching word when isAllowOnceEnabled then returns false`() {
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "yeah", WORD))
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "no", WORD))
    }

    @Test
    fun `showAlwaysAllow is true only for sensitive`() {
        assertFalse(HitlConfirmationState.showAlwaysAllow(Risk.Readonly))
        assertTrue(HitlConfirmationState.showAlwaysAllow(Risk.Sensitive))
        assertFalse(HitlConfirmationState.showAlwaysAllow(Risk.Destructive))
    }

    @Test
    fun `showTypedConfirmRow is true only for destructive`() {
        assertFalse(HitlConfirmationState.showTypedConfirmRow(Risk.Readonly))
        assertFalse(HitlConfirmationState.showTypedConfirmRow(Risk.Sensitive))
        assertTrue(HitlConfirmationState.showTypedConfirmRow(Risk.Destructive))
    }

    @Test
    fun `given the Russian word typed in another case when matched then it confirms`() {
        assertTrue(typedConfirmMatches(input = " ДА ", keyword = "да"))
        assertTrue(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "Да", "да"))
    }

    @Test
    fun `given the English word while the interface asks for the Russian one when matched then it does not confirm`() {
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "yes", "да"))
    }

    @Test
    fun `given a confirmation word left blank by a translation when typed nothing then nothing is approved`() {
        // A blank word must close the gate, not open it to an empty field.
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "", ""))
        assertFalse(HitlConfirmationState.isAllowOnceEnabled(Risk.Destructive, "  ", " "))
        assertFalse(typedConfirmMatches(input = "", keyword = ""))
    }

    private companion object {
        /** The English value of `knotwork_typed_confirm_word`. */
        const val WORD = "yes"
    }
}
