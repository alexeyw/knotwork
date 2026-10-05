package app.knotwork.design.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.misc.KnotworkTestProbeRow
import app.knotwork.design.components.misc.KnotworkWarningBanner
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Everything the provider detail screen renders, with every string already
 * resolved by `:app`.
 *
 * Split out of `ProviderDetailScreen` because that screen was composed entirely
 * in `:app` and therefore had no visual baseline at all — which is how it grew a
 * look of its own (its own title style, a grey explanatory paragraph, bare
 * Material sliders) without anything noticing. There was nothing to compare it
 * against.
 *
 * The provider itself is not a parameter. `:app` resolves which inputs a
 * provider has — an address, a fixed address, a key, a built-in model list or a
 * typed id — into [ProviderDetailViewState], so this module never learns the
 * provider vocabulary and a new provider needs no change here. The form is open,
 * not a collapsible card: on its own screen, the provider's fields are the screen.
 *
 * @param state Resolved copy and values.
 * @param modifier Layout modifier from the caller.
 * @param callbacks Edits and navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailContent(
    state: ProviderDetailViewState,
    modifier: Modifier = Modifier,
    callbacks: ProviderDetailCallbacks = noopProviderDetailCallbacks(),
) {
    Scaffold(
        modifier = modifier.testTag(PROVIDER_DETAIL_ROOT_TEST_TAG),
        containerColor = MaterialTheme.colorScheme.surface,
        // The outer app shell already absorbs the system bars; defaulting to
        // safeDrawing here would double-count the insets.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            KnotworkTopAppBarShell {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title,
                            style = KnotworkTextStyles.TitleMd,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = callbacks.onBack) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = state.backContentDescription,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(KnotworkTheme.spacing.sp4),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp5),
            ) {
                ProviderFields(state = state, callbacks = callbacks)
                CloudRetrySection(state = state.retry, callbacks = callbacks)
            }
        }
    }
    state.modelSheet?.let { sheet ->
        ModelPickerSheet(
            state = sheet,
            onPick = { id ->
                callbacks.onModelChange(id)
                callbacks.onModelSheetDismiss()
            },
            onDismiss = callbacks.onModelSheetDismiss,
        )
    }
}

/**
 * The provider's fields in the order a user fills them, with the test row between the last field
 * it reads and the first it feeds: address → key → *Test connection* → model.
 *
 * @param state Resolved fields.
 * @param callbacks Edits and actions.
 */
@Composable
private fun ProviderFields(state: ProviderDetailViewState, callbacks: ProviderDetailCallbacks) {
    state.address?.let { AddressField(it, callbacks.onAddressChange) }
    // Unencrypted LAN traffic is refused until the user says otherwise for this exact address. A
    // banner rather than a dialog because the address persists on every keystroke — a dialog
    // would open mid-typing.
    state.cleartextConsent?.let { consent ->
        KnotworkWarningBanner(
            text = consent.body,
            actionLabel = consent.actionLabel,
            onAction = callbacks.onApproveCleartextOrigin,
            testTag = CLEARTEXT_CONSENT_BANNER_TEST_TAG,
        )
    }
    state.fixedAddress?.let { FixedAddress(it) }
    state.apiKey?.let { KeyField(it, callbacks.onApiKeyChange) }
    KnotworkTestProbeRow(state = state.test, onRun = callbacks.onTestRun, onCancel = callbacks.onTestCancel)
    ModelField(state.model, onChange = callbacks.onModelChange, onChoose = callbacks.onChooseModel)
    state.contextWindow?.let { window ->
        Column {
            FieldHeader(label = window.label)
            OutlinedTextField(
                value = window.value,
                onValueChange = callbacks.onContextWindowChange,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = KnotworkTextStyles.MonoBase,
                colors = fieldColors(),
            )
        }
    }
}

/** The address of a server the user runs; wraps rather than scrolls, so its end stays in view. */
@Composable
private fun AddressField(state: ProviderAddressUi, onChange: (String) -> Unit) {
    Column {
        FieldHeader(label = state.label)
        OutlinedTextField(
            value = state.value,
            onValueChange = onChange,
            placeholder = { Text(state.placeholder, style = KnotworkTextStyles.MonoBase) },
            modifier = Modifier.fillMaxWidth().testTag(PROVIDER_ADDRESS_FIELD_TEST_TAG),
            maxLines = WRAPPING_FIELD_MAX_LINES,
            isError = state.error != null,
            textStyle = KnotworkTextStyles.MonoBase,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            colors = fieldColors(warn = state.error == null && state.refusal != null),
        )
        when {
            state.error != null -> FieldNote(text = state.error, tone = NoteTone.Error)
            state.refusal != null -> FieldNote(text = state.refusal, tone = NoteTone.Warn)
        }
        state.hint?.let { FieldNote(text = it, tone = NoteTone.Hint) }
    }
}

/** A fixed address, read-only: a lock and the address in mono, no field box. */
@Composable
private fun FixedAddress(state: ProviderFixedAddressUi) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldHeader(label = state.label)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(NOTE_GLYPH_SIZE),
            )
            Text(text = state.value, style = KnotworkTextStyles.MonoBase, color = KnotworkTheme.extended.onSurface2)
        }
    }
}

/** The API key, masked, and asking the keyboard for a password so it neither suggests nor learns it. */
@Composable
private fun KeyField(state: ProviderKeyUi, onChange: (String) -> Unit) {
    Column {
        FieldHeader(label = state.label, marker = state.marker)
        OutlinedTextField(
            value = state.value,
            onValueChange = onChange,
            placeholder = state.placeholder?.let { { Text(it, style = KnotworkTextStyles.MonoBase) } },
            modifier = Modifier.fillMaxWidth().testTag(PROVIDER_KEY_FIELD_TEST_TAG),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            textStyle = KnotworkTextStyles.MonoBase,
            colors = fieldColors(),
        )
        state.hint?.let { FieldNote(text = it, tone = NoteTone.Hint) }
    }
}

/**
 * The model field: a dropdown over a built-in list, or a typed id that a successful test can fill
 * from the server's list through *Choose* — beside the field, or under it at 150 % and above.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(state: ProviderModelUi, onChange: (String) -> Unit, onChoose: () -> Unit) {
    Column {
        FieldHeader(label = state.label, marker = state.marker)
        if (state.options.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = state.value,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    textStyle = KnotworkTextStyles.MonoBase,
                    colors = fieldColors(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, style = KnotworkTextStyles.MonoBase) },
                            onClick = {
                                onChange(option)
                                expanded = false
                            },
                        )
                    }
                }
            }
        } else {
            val below = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
            val choose = state.chooseLabel?.let { label ->
                @Composable { ChooseAction(label = label, onClick = onChoose) }
            }
            OutlinedTextField(
                value = state.value,
                onValueChange = onChange,
                placeholder = state.placeholder?.let { { Text(it, style = KnotworkTextStyles.MonoBase) } },
                modifier = Modifier.fillMaxWidth().testTag(PROVIDER_MODEL_FIELD_TEST_TAG),
                maxLines = WRAPPING_FIELD_MAX_LINES,
                textStyle = KnotworkTextStyles.MonoBase,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = choose.takeUnless { below },
                colors = fieldColors(),
            )
            if (below) choose?.invoke()
            state.note?.let { FieldNote(text = it, tone = NoteTone.Hint) }
        }
    }
}

/** *Choose*: opens the server's model list. */
@Composable
private fun ChooseAction(label: String, onClick: () -> Unit) {
    KnotworkTextButton(
        text = label,
        onClick = onClick,
        size = KnotworkButtonSize.Sm,
        leadingIcon = AppIcons.Search,
        modifier = Modifier.testTag(PROVIDER_MODEL_CHOOSE_TEST_TAG),
    )
}

/** A field's label, with an optional mono marker ("optional", "required") after it. */
@Composable
private fun FieldHeader(label: String, marker: String? = null) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.padding(bottom = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = label,
            style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        marker?.let {
            Text(
                text = it,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/** How a note under a field reads: a plain hint, an error, or a rule's refusal. */
private enum class NoteTone { Hint, Error, Warn }

/** A note under a field; an error or a refusal leads with its own glyph, so colour is not alone. */
@Composable
private fun FieldNote(text: String, tone: NoteTone) {
    val color = when (tone) {
        NoteTone.Hint -> KnotworkTheme.extended.onSurface2
        NoteTone.Error -> KnotworkTheme.extended.signalError
        NoteTone.Warn -> KnotworkTheme.extended.signalWarn
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
    ) {
        val glyph = when (tone) {
            NoteTone.Hint -> null
            NoteTone.Error -> AppIcons.AlertCircle
            NoteTone.Warn -> AppIcons.Block
        }
        glyph?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = color,
                modifier = Modifier.padding(top = NOTE_GLYPH_TOP).size(NOTE_GLYPH_SIZE),
            )
        }
        Text(text = text, style = KnotworkTextStyles.BodySm, color = color)
    }
}

/** The field colours of the settings forms; [warn] borders a field a rule refuses in warn ink. */
@Composable
private fun fieldColors(warn: Boolean = false) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = if (warn) KnotworkTheme.extended.signalWarn else MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = if (warn) KnotworkTheme.extended.signalWarn else KnotworkTheme.extended.outlineStrong,
    cursorColor = MaterialTheme.colorScheme.primary,
    unfocusedContainerColor = KnotworkTheme.extended.surface1,
    focusedContainerColor = KnotworkTheme.extended.surface1,
)

/**
 * The cloud-retry policy, shown on every provider because it applies to all of
 * them uniformly.
 *
 * Built from the settings components rather than raw Material ones. This section
 * had grown its own look — a title in `TitleMd`, a grey paragraph, `Text` + bare
 * `Slider` pairs — so a reader arriving from any other settings screen met a
 * different visual language and none of the help affordance the rest of Settings
 * had gained.
 *
 * @param state Resolved slider values, labels and bounds.
 * @param callbacks Slider edits.
 */
@Composable
private fun CloudRetrySection(state: CloudRetryViewState, callbacks: ProviderDetailCallbacks) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp4),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        CompositionLocalProvider(LocalSettingsRowAnchor provides state.sectionAnchor) {
            SettingsSectionLabel(text = state.sectionTitle) {
                SettingsHintGlyph(settingName = state.sectionTitle)
            }
            // The paragraph that used to sit here explained the policy in muted
            // body text — the slot this app reserves for machine state, and the
            // reason such text went unread. It is the section's hint now.
            SettingsHintBody()
        }
        CompositionLocalProvider(LocalSettingsRowAnchor provides state.attemptsAnchor) {
            KnotworkParamSlider(
                label = state.attemptsLabel,
                valueLabel = state.attemptsValueLabel,
                value = state.attempts.toFloat(),
                onValueChange = { callbacks.onRetryAttemptsChange(it.toInt()) },
                valueRange = state.attemptsRange,
                steps = state.attemptsSteps,
            )
        }
        CompositionLocalProvider(LocalSettingsRowAnchor provides state.delayAnchor) {
            KnotworkParamSlider(
                label = state.delayLabel,
                valueLabel = state.delayValueLabel,
                value = state.delayMs.toFloat(),
                onValueChange = { callbacks.onRetryDelayChange(it.toLong()) },
                valueRange = state.delayRange,
            )
        }
    }
}

/** Root test tag of the provider detail surface. */
const val PROVIDER_DETAIL_ROOT_TEST_TAG: String = "provider_detail_root"

/** Test tag of the cleartext-consent banner. */
const val CLEARTEXT_CONSENT_BANNER_TEST_TAG: String = "cleartext_consent_banner"

/** Test tag of the server-address field. */
const val PROVIDER_ADDRESS_FIELD_TEST_TAG: String = "provider_address_field"

/** Test tag of the API-key field. */
const val PROVIDER_KEY_FIELD_TEST_TAG: String = "provider_key_field"

/** Test tag of the typed model-id field. */
const val PROVIDER_MODEL_FIELD_TEST_TAG: String = "provider_model_field"

/** Test tag of the model field's *Choose* action. */
const val PROVIDER_MODEL_CHOOSE_TEST_TAG: String = "provider_model_choose"

/** Lines an address or a model id wraps to before it scrolls — its end is often what matters. */
private const val WRAPPING_FIELD_MAX_LINES = 3

/** Font scale from which *Choose* moves under the model field. */
private const val STACKED_FONT_SCALE = 1.5f

private val NOTE_GLYPH_SIZE = 14.dp
private val NOTE_GLYPH_TOP = 2.dp
