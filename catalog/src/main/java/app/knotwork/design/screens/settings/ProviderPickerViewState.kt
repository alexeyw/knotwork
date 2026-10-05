package app.knotwork.design.screens.settings

/**
 * The provider picker's contents, already resolved.
 *
 * @property title Screen title.
 * @property backContentDescription Accessible label of the back control.
 * @property groups The providers on offer, grouped — hosted ones, then servers the user runs —
 *   in the order they are shown.
 * @property addedLabel The marker on a provider already set up, e.g. "added".
 */
data class ProviderPickerViewState(
    val title: String,
    val backContentDescription: String,
    val groups: List<ProviderPickerGroupUi>,
    val addedLabel: String,
)

/**
 * One group of the picker.
 *
 * @property title The group heading, e.g. "Hosted".
 * @property rows Its providers, in order.
 */
data class ProviderPickerGroupUi(val title: String, val rows: List<ProviderPickerRowUi>)

/**
 * One provider on the picker.
 *
 * @property id Opaque identifier handed back on tap; `:app` maps it to its own
 *   provider type, so this module never learns that vocabulary.
 * @property title The provider's name.
 * @property description A line under the name — only for an entry named after a protocol rather
 *   than a product, which needs saying what qualifies.
 * @property added Whether the provider is already set up; tapping it still opens its screen.
 */
data class ProviderPickerRowUi(
    val id: String,
    val title: String,
    val description: String? = null,
    val added: Boolean = false,
)
