package app.knotwork.design.screens.settings

import app.knotwork.design.components.misc.TestProbeUi

/**
 * Everything the provider detail surface needs, already resolved.
 *
 * No provider enum: `:app` decides which fields a provider has — an address for a server the user
 * runs, a fixed address for OpenRouter and Groq, a key, a built-in model list or a typed model id
 * — so adding a provider never reaches this module. A field the provider has no use for is `null`
 * and is not drawn.
 *
 * The fields come in the order a user fills them, and the test row sits between the last field it
 * reads and the first one it feeds: address → key → *Test connection* → model.
 *
 * @property title Screen title: the provider's name.
 * @property backContentDescription Accessible label of the back control.
 * @property address The server address, for a server the user runs.
 * @property cleartextConsent Pending consent for an unencrypted origin, or `null`.
 * @property fixedAddress Where a provider with a fixed address sends, shown read-only.
 * @property apiKey The key field, or `null` for a provider that takes none.
 * @property test The *Test connection* row.
 * @property model The model field.
 * @property contextWindow The context-window field, for a provider that has one.
 * @property modelSheet The model list, while it is open.
 * @property retry The cloud-retry policy, which applies to every provider.
 */
data class ProviderDetailViewState(
    val title: String,
    val backContentDescription: String,
    val test: TestProbeUi,
    val model: ProviderModelUi,
    val retry: CloudRetryViewState,
    val address: ProviderAddressUi? = null,
    val cleartextConsent: CleartextConsentUi? = null,
    val fixedAddress: ProviderFixedAddressUi? = null,
    val apiKey: ProviderKeyUi? = null,
    val contextWindow: ProviderContextWindowUi? = null,
    val modelSheet: ModelSheetUi? = null,
)

/**
 * The address of a server the user runs.
 *
 * A reason the address cannot be used is said once, here, while it is typed: [error] when it is
 * not an address at all, [refusal] when it is one a rule refuses — a warning, not an error, since
 * the address is well-formed.
 *
 * @property label Field label.
 * @property value The address as typed.
 * @property placeholder Example address shown while empty.
 * @property hint A note that stays under the field, e.g. "Include /v1".
 * @property error Why the address is not an address, or `null`.
 * @property refusal Why a rule refuses the address, or `null`.
 */
data class ProviderAddressUi(
    val label: String,
    val value: String,
    val placeholder: String,
    val hint: String? = null,
    val error: String? = null,
    val refusal: String? = null,
)

/**
 * The fixed address of a provider such as OpenRouter: shown, never edited, without a field box —
 * a box looks editable.
 *
 * @property label "Sends to".
 * @property value The address.
 */
data class ProviderFixedAddressUi(val label: String, val value: String)

/**
 * The API key field.
 *
 * @property label Field label.
 * @property value The key as typed; drawn masked.
 * @property marker "optional" or "required", in the mono slot after the label; `null` for none.
 * @property placeholder Example key prefix shown while empty.
 * @property hint A note under the field.
 */
data class ProviderKeyUi(
    val label: String,
    val value: String,
    val marker: String? = null,
    val placeholder: String? = null,
    val hint: String? = null,
)

/**
 * The model field: a built-in list to pick from, or a typed id.
 *
 * @property label Field label.
 * @property value The chosen or typed id.
 * @property options A built-in list; non-empty makes the field a dropdown.
 * @property marker "required", in the mono slot after the label; `null` for none.
 * @property placeholder Example id shown while a typed field is empty.
 * @property note A note under a typed field — what to do before a test, or that the server sent
 *   no list.
 * @property chooseLabel The label of the action that opens the server's list, shown on a typed
 *   field once a test has brought one; `null` hides the action.
 */
data class ProviderModelUi(
    val label: String,
    val value: String,
    val options: List<String> = emptyList(),
    val marker: String? = null,
    val placeholder: String? = null,
    val note: String? = null,
    val chooseLabel: String? = null,
)

/**
 * The context-window field.
 *
 * @property label Field label.
 * @property value Current value, as text so a partial input can be shown.
 */
data class ProviderContextWindowUi(val label: String, val value: String)

/**
 * The searchable list of a server's model ids.
 *
 * @property source Whose list it is, e.g. "OpenRouter".
 * @property ids The ids, in the server's order.
 * @property selected The id currently in the field; ticked in the list.
 */
data class ModelSheetUi(val source: String, val ids: List<String>, val selected: String)

/**
 * The unencrypted-origin notice.
 *
 * @property body The sentence naming the origin.
 * @property actionLabel Label of the approve action.
 */
data class CleartextConsentUi(val body: String, val actionLabel: String)

/**
 * The cloud-retry policy sliders.
 *
 * Bounds and step counts arrive resolved rather than being constants here: they
 * come from the same settings defaults the store coerces against, and a second
 * copy in this module would be a range that could disagree with the one actually
 * enforced.
 *
 * @property sectionTitle Section heading, also the hint's subject.
 * @property sectionAnchor Hint anchor of the section heading.
 * @property attempts Current attempt budget; `1` disables retries.
 * @property attemptsLabel Label of the attempts slider.
 * @property attemptsValueLabel Current attempts, rendered.
 * @property attemptsRange Allowed attempts.
 * @property attemptsSteps Discrete stops between the bounds.
 * @property attemptsAnchor Hint anchor of the attempts slider.
 * @property delayMs Current base backoff delay.
 * @property delayLabel Label of the delay slider.
 * @property delayValueLabel Current delay, rendered with its unit.
 * @property delayRange Allowed delay.
 * @property delayAnchor Hint anchor of the delay slider.
 */
data class CloudRetryViewState(
    val sectionTitle: String,
    val sectionAnchor: String,
    val attempts: Int,
    val attemptsLabel: String,
    val attemptsValueLabel: String,
    val attemptsRange: ClosedFloatingPointRange<Float>,
    val attemptsSteps: Int,
    val attemptsAnchor: String,
    val delayMs: Long,
    val delayLabel: String,
    val delayValueLabel: String,
    val delayRange: ClosedFloatingPointRange<Float>,
    val delayAnchor: String,
)

/**
 * Callback bag for [ProviderDetailContent].
 *
 * @property onBack Pop back to the provider picker.
 * @property onApiKeyChange The key field changed.
 * @property onModelChange The model field changed, or a model was picked.
 * @property onAddressChange The address field changed.
 * @property onContextWindowChange The context-window field changed.
 * @property onApproveCleartextOrigin The user allowed the unencrypted origin.
 * @property onRetryAttemptsChange The attempts slider settled.
 * @property onRetryDelayChange The delay slider settled.
 * @property onTestRun *Test* or *Test again* was pressed.
 * @property onTestCancel *Cancel* was pressed on a running test.
 * @property onChooseModel *Choose* was pressed on the model field.
 * @property onModelSheetDismiss The model list was closed without a pick.
 */
data class ProviderDetailCallbacks(
    val onBack: () -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onModelChange: (String) -> Unit,
    val onAddressChange: (String) -> Unit,
    val onContextWindowChange: (String) -> Unit,
    val onApproveCleartextOrigin: () -> Unit,
    val onRetryAttemptsChange: (Int) -> Unit,
    val onRetryDelayChange: (Long) -> Unit,
    val onTestRun: () -> Unit = {},
    val onTestCancel: () -> Unit = {},
    val onChooseModel: () -> Unit = {},
    val onModelSheetDismiss: () -> Unit = {},
)

/** Inert callbacks, so a preview or a snapshot needs none. */
fun noopProviderDetailCallbacks(): ProviderDetailCallbacks = ProviderDetailCallbacks(
    onBack = {},
    onApiKeyChange = {},
    onModelChange = {},
    onAddressChange = {},
    onContextWindowChange = {},
    onApproveCleartextOrigin = {},
    onRetryAttemptsChange = {},
    onRetryDelayChange = {},
)
