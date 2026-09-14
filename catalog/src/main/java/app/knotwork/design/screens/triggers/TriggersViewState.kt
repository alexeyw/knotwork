package app.knotwork.design.screens.triggers

/**
 * Visual variant of the Triggers list surface.
 */
enum class TriggersVisualState {
    /** Initial fetch in progress — skeleton rows. */
    Loading,

    /** Normal list of trigger rows. */
    Default,

    /** No triggers yet — the bespoke teaching empty state (model explanation). */
    Empty,

    /** Fatal load error — message + retry CTA. */
    Error,
}

/**
 * The four first-wave condition types, surfaced in the editor's type selector
 * and used to pick a row's condition glyph. Mirrors the domain
 * `TriggerCondition` sealed hierarchy.
 */
enum class TriggerConditionType {
    /** Repeats every N minutes / hours. */
    Interval,

    /** Fires once a day at a time of day. */
    Daily,

    /** Fires when the charger is connected. */
    Charging,

    /** Fires when a network (any / Wi-Fi) connects. */
    Network,
}

/**
 * One row in the Triggers list.
 *
 * @property id stable trigger id.
 * @property name display name (bold title; clamps to one line).
 * @property conditionLabel pre-formatted, human-readable condition line
 *   (e.g. "Every day at 08:00"); clamps to one line.
 * @property conditionType which condition glyph to render on the leading tile
 *   and the condition line.
 * @property pipelineName bound pipeline display name, or `null` when the trigger
 *   is unbound (inert). A `null` name renders the "tap to bind" hint and a
 *   disabled switch.
 * @property enabled current on/off state; ignored visually when unbound (an
 *   unbound trigger is always inert).
 * @property health background-reliability badge, or `null` when the trigger is
 *   unbound (inert, never evaluated) and therefore carries no health signal.
 */
data class TriggerRowUi(
    val id: String,
    val name: String,
    val conditionLabel: String,
    val conditionType: TriggerConditionType,
    val pipelineName: String?,
    val enabled: Boolean,
    val health: TriggerHealthUi? = null,
) {
    /** Whether the trigger is bound to a pipeline (and therefore can fire). */
    val isBound: Boolean get() = pipelineName != null
}

/**
 * One selectable pipeline in the editor's binding picker.
 *
 * @property id stable pipeline id (the value bound into `Trigger.pipelineId`).
 * @property name display name shown in the picker.
 */
data class TriggerPipelineOptionUi(val id: String, val name: String)

/**
 * Full-screen editor body state.
 *
 * The interval condition is modelled as either a **preset** selection
 * ([intervalCustom] `false`, [intervalMinutes] matching one of the preset chips)
 * or a **custom** value ([intervalCustom] `true`, [intervalCustomAmount] +
 * [intervalCustomUnitHours]); the host resolves the effective minutes and the
 * validity ([intervalError]).
 *
 * @property id `null` for a new draft, non-null when editing an existing trigger.
 * @property name current Name value.
 * @property type the selected condition type.
 * @property intervalCustom `true` when the Custom interval panel is shown
 *   instead of the preset chips.
 * @property intervalMinutes the effective preset interval in minutes (drives the
 *   selected chip when [intervalCustom] is `false`).
 * @property intervalCustomAmount raw text in the Custom amount field.
 * @property intervalCustomUnitHours `true` interprets the Custom amount as hours,
 *   `false` as minutes.
 * @property intervalError `true` highlights the interval control as invalid
 *   (below the 15-minute floor).
 * @property dailyHour selected hour `0..23` for the Daily condition.
 * @property dailyMinute selected minute `0..59` for the Daily condition.
 * @property wifiOnly `true` restricts the Network condition to Wi-Fi.
 * @property ssids the Wi-Fi network names the Network condition is scoped to;
 *   empty means "any network / any Wi-Fi". A non-empty list shows the names as
 *   removable chips and implies Wi-Fi.
 * @property pipelines the pipelines offered in the binding picker.
 * @property pipelineName the resolved bound pipeline name, or `null` (unbound).
 * @property prompt the input message handed to the pipeline on fire.
 * @property enabled the on/off value edited inside the editor.
 * @property nameError `true` highlights the Name field as invalid.
 * @property canSave whether Save is enabled (name non-blank and a valid
 *   condition).
 */
@Suppress("LongParameterList") // Documented public view-state; folding fields hides the editor shape.
data class TriggerEditorUi(
    val id: String?,
    val name: String,
    val type: TriggerConditionType,
    val intervalCustom: Boolean,
    val intervalMinutes: Long,
    val intervalCustomAmount: String,
    val intervalCustomUnitHours: Boolean,
    val intervalError: Boolean,
    val dailyHour: Int,
    val dailyMinute: Int,
    val wifiOnly: Boolean,
    val ssids: List<String>,
    val pipelines: List<TriggerPipelineOptionUi>,
    val pipelineName: String?,
    val prompt: String,
    val enabled: Boolean,
    val nameError: Boolean,
    val canSave: Boolean,
) {
    /** Zero-padded `HH:mm` rendering of the Daily time. */
    val dailyTimeLabel: String get() = "%02d:%02d".format(dailyHour, dailyMinute)
}

/**
 * Delete-confirmation dialog state.
 *
 * @property triggerName the name being deleted (shown in the dialog title/body).
 */
data class TriggerDeleteUi(val triggerName: String)

/**
 * Top-level immutable input to `TriggersContent`.
 *
 * @property visualState rendering branch.
 * @property rows the trigger rows.
 * @property openMenuRowId id of the row whose overflow menu is open, or `null`.
 * @property subtitle TopAppBar subtitle (e.g. `"3 triggers · 2 active"`).
 * @property errorMessage user-visible error when [visualState] is
 *   [TriggersVisualState.Error].
 */
data class TriggersViewState(
    val visualState: TriggersVisualState,
    val rows: List<TriggerRowUi> = emptyList(),
    val openMenuRowId: String? = null,
    val subtitle: String = "",
    val errorMessage: String? = null,
) {
    init {
        require((visualState == TriggersVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/** One-shot callbacks consumed by `TriggersContent`. */
@Suppress("LongParameterList") // Documented public API.
class TriggersCallbacks(
    val onBack: () -> Unit = {},
    val onNewTrigger: () -> Unit = {},
    val onRowClick: (String) -> Unit = {},
    val onRowToggle: (String) -> Unit = {},
    val onRowMenuOpen: (String) -> Unit = {},
    val onRowMenuDismiss: () -> Unit = {},
    val onEditTrigger: (String) -> Unit = {},
    val onDeleteTrigger: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onShareJournal: () -> Unit = {},
    val onSaveJournal: () -> Unit = {},
    /**
     * Invoked by the top bar's documentation action. The host opens the guide's
     * "Triggers" section — the screen where external testers most often asked
     * why nothing had fired yet.
     */
    val onOpenDocumentation: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopTriggersCallbacks(): TriggersCallbacks = TriggersCallbacks()

/** One-shot callbacks consumed by `TriggerEditorContent`. */
@Suppress("LongParameterList") // Documented public API.
class TriggerEditorCallbacks(
    val onClose: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onTypeChange: (TriggerConditionType) -> Unit = {},
    val onIntervalPreset: (Long) -> Unit = {},
    val onIntervalCustomToggle: () -> Unit = {},
    val onIntervalCustomAmountChange: (String) -> Unit = {},
    val onIntervalUnitChange: (Boolean) -> Unit = {},
    val onDailyTimeChange: (Int, Int) -> Unit = { _, _ -> },
    val onWifiOnlyToggle: () -> Unit = {},
    val onSsidAdd: (String) -> Unit = {},
    val onSsidRemove: (String) -> Unit = {},
    val onPipelineSelect: (String?) -> Unit = {},
    val onPromptChange: (String) -> Unit = {},
    val onEnabledToggle: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** Convenience factory returning a no-op editor callback bundle. */
fun noopTriggerEditorCallbacks(): TriggerEditorCallbacks = TriggerEditorCallbacks()
