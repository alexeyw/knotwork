package app.knotwork.design.components.chat

import app.knotwork.design.components.chips.Risk

/**
 * Pure-Kotlin state helpers backing [HitlConfirmationCard]. Extracted from the
 * composable so the gating logic is unit-testable on the JVM without spinning
 * up Robolectric / Compose.
 */
object HitlConfirmationState {

    /**
     * Resolves the enabled-state of the "Allow once" CTA. Mirrors the rules
     * for the HITL confirmation card:
     *  - `Readonly` is auto-approved upstream; the CTA is never shown — return
     *    `true` defensively so a misuse renders a tappable button.
     *  - `Sensitive` is always allow-able; the user just taps Allow.
     *  - `Destructive` requires the user to type [confirmWord] (see
     *    [matchesConfirmWord]).
     *
     * @param risk risk tier of the prompt.
     * @param pendingTypedConfirm raw value of the destructive type-confirm
     * `OutlinedTextField`.
     * @param confirmWord the word to type, in the interface language — the
     * `knotwork_typed_confirm_word` resource.
     * @return `true` if the Allow CTA should accept taps.
     */
    fun isAllowOnceEnabled(risk: Risk, pendingTypedConfirm: String, confirmWord: String): Boolean = when (risk) {
        Risk.Readonly, Risk.Sensitive -> true
        Risk.Destructive -> matchesConfirmWord(pendingTypedConfirm, confirmWord)
    }

    /**
     * Whether a typed confirmation is the word: letter case and surrounding
     * whitespace do not matter, so "Yes " confirms as "yes" does.
     *
     * @param typed what the user typed.
     * @param confirmWord the word to type, in the interface language.
     * @return `true` when [typed] is [confirmWord].
     */
    fun matchesConfirmWord(typed: String, confirmWord: String): Boolean =
        typed.trim().equals(confirmWord.trim(), ignoreCase = true)

    /**
     * Whether the "Always allow" affordance should be visible for the prompt.
     *  - `Readonly` is auto-allowed and never asks again.
     *  - `Sensitive` exposes "Always allow" so the user can promote the tool.
     *  - `Destructive` deliberately does not — too risky to remember.
     *
     * @param risk risk tier of the prompt.
     * @return `true` if the affordance should render.
     */
    fun showAlwaysAllow(risk: Risk): Boolean = risk == Risk.Sensitive

    /**
     * Whether the destructive "Type yes to confirm" row should render.
     *
     * @param risk risk tier of the prompt.
     * @return `true` when the row should render (Destructive only).
     */
    fun showTypedConfirmRow(risk: Risk): Boolean = risk == Risk.Destructive
}
