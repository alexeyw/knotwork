package app.knotwork.design.screens.triggers

/**
 * At-a-glance background-reliability state of a trigger, shown as the list-row
 * health-badge. The catalog mirror of the domain `TriggerHealthStatus`; the app
 * maps one onto the other so the catalog stays framework- and domain-free.
 */
enum class TriggerHealthUi {
    /** Evaluated on cadence and the last run (if any) succeeded. */
    Healthy,

    /** Not evaluated for longer than expected — the poll looks starved. */
    Overdue,

    /** Being evaluated, but the most recent fired run ended in error. */
    LastRunFailed,
}

/**
 * Where a journal evaluation was initiated from — the catalog mirror of the
 * domain `TriggerEvaluationSource`.
 */
enum class TriggerJournalSourceUi {
    /** The periodic watch worker (the poll heartbeat). */
    Poll,

    /** A device-state event listener (charging / connectivity edge). */
    Event,

    /** The charging re-check sweep. */
    Charging,
}

/**
 * The decision an evaluation reached — the catalog mirror of the domain
 * `TriggerEvaluationVerdict`. Drives the leading timeline-node tile and the
 * first line of a journal entry.
 */
enum class TriggerJournalVerdictUi {
    /** Condition met; a background run was enqueued. */
    Fired,

    /** An event trigger's edge latch was reset; no run. */
    ReArmed,

    /** Evaluated but did not fire; a typed [TriggerJournalSkipReasonUi] follows. */
    Skipped,
}

/**
 * Why a [TriggerJournalVerdictUi.Skipped] entry did not fire — the catalog mirror
 * of the domain `TriggerSkipReason`. The presentation layer renders each as a
 * human sentence, never the constant name.
 */
enum class TriggerJournalSkipReasonUi {
    /** The trigger was turned off. */
    Disabled,

    /** No pipeline is bound. */
    Unbound,

    /** The condition was not satisfied at the moment named on the row. */
    ConditionNotMet,

    /** It had already fired for this window. */
    AlreadyFired,
}

/**
 * Terminal fate of the run a [TriggerJournalVerdictUi.Fired] entry started — the
 * catalog mirror of the domain `TriggerRunOutcome`, plus a synthetic
 * [Pending] for a fired row whose run has not settled yet.
 */
enum class TriggerJournalOutcomeUi {
    /** Fired, run not yet settled — a pulsing "Running…" line. */
    Pending,

    /** Run completed successfully. */
    Success,

    /** Run failed on its own (a product defect); carries an error string. */
    Failure,

    /** The platform killed the hosting process mid-run — never the user's fault. */
    CancelledBySystem,

    /** The user (or a torn-down service) deliberately stopped the run. */
    Cancelled,

    /** The run parked on a background approval that timed out. */
    HitlTimeout,

    /** An autonomous-run ceiling stopped the run — the guard working, not a defect. */
    StoppedByCeiling,
}

/**
 * Whether a fired run stopped to ask the user something, and how that ended — the
 * catalog mirror of the domain `TriggerHitlResolution`.
 *
 * Describes the **latest** gate of the run. A run that asks more than once is
 * rare, and the entry answers a single question for the reader ("did it need me,
 * and what came of it?"); the full count stays in the diagnostic journal export,
 * where the analysis that cares about it happens.
 */
enum class TriggerJournalHitlUi {
    /** Still waiting for the user — either live, or parked in the shade. */
    Waiting,

    /** The user approved the staged tool call. */
    Approved,

    /** The user denied the staged tool call. */
    Denied,

    /** The user answered the clarifying question. */
    Answered,

    /** Nobody answered before the approval window closed; the run was failed. */
    TimedOut,

    /** The request never reached the user (it could not be parked, or was discarded). */
    Abandoned,
}

/**
 * One journal entry projected for display. Structured verdict / source / outcome
 * carry the icon + label; the dynamic strings (timestamp, failure error) are
 * pre-resolved by the app so the catalog neither formats time nor holds locale.
 *
 * @property id Stable evaluation id (list key).
 * @property source Where the evaluation came from.
 * @property verdict The decision reached.
 * @property outcome The settled run outcome for a [TriggerJournalVerdictUi.Fired]
 *   entry (or [TriggerJournalOutcomeUi.Pending]); `null` for non-fired verdicts.
 * @property outcomeError Short failure description appended after a
 *   [TriggerJournalOutcomeUi.Failure] label; `null` otherwise.
 * @property skipReason The typed reason for a [TriggerJournalVerdictUi.Skipped]
 *   entry; `null` otherwise.
 * @property skipMomentLabel The clock time named by the
 *   [TriggerJournalSkipReasonUi.ConditionNotMet] sentence (e.g. "07:15").
 * @property timestampLabel Pre-resolved moment label ("just now" / "12m ago" /
 *   "07:15").
 * @property hitl How the run's human-in-the-loop gate ended, or `null` when the
 *   run never asked for anything — which is the ordinary case, so the line is
 *   absent rather than reading "no approval needed" on every entry.
 * @property hitlParked Whether that gate had to wait on a notification instead of
 *   being answered on the spot. Rendered as a qualifier on the [hitl] line, and
 *   the reason the line exists at all: an approval given from the shade is what
 *   proves background HITL still reaches the user.
 */
data class TriggerJournalEntryUi(
    val id: String,
    val source: TriggerJournalSourceUi,
    val verdict: TriggerJournalVerdictUi,
    val outcome: TriggerJournalOutcomeUi? = null,
    val outcomeError: String? = null,
    val skipReason: TriggerJournalSkipReasonUi? = null,
    val skipMomentLabel: String? = null,
    val timestampLabel: String,
    val hitl: TriggerJournalHitlUi? = null,
    val hitlParked: Boolean = false,
)

/**
 * A contiguous run of journal entries recorded on the same device-local day.
 *
 * @property headerLabel Pre-resolved day label ("Today" / "Yesterday" /
 *   "Mon 14 Jul").
 * @property entries The entries, newest first.
 */
data class TriggerJournalDayGroupUi(val headerLabel: String, val entries: List<TriggerJournalEntryUi>)

/**
 * Rendering branch of the journal section within the detail screen.
 */
enum class TriggerJournalVisualState {
    /** Initial fetch in progress — skeleton rows. */
    Loading,

    /** No evaluations yet — the teaching empty state. */
    Empty,

    /** One or more day-grouped entries. */
    Populated,
}

/**
 * Immutable input to `TriggerDetailContent` — the trigger's identity header plus
 * its grouped evaluation journal.
 *
 * @property name Trigger name (top-bar title + header identity).
 * @property conditionType Condition glyph shown in the header "When" row.
 * @property conditionLabel Pre-formatted condition line (e.g. "Every 30 minutes").
 * @property pipelineName Bound pipeline name, or `null` when unbound (inert).
 * @property enabled Current on/off state (the header State row + switch).
 * @property staleSinceLabel Non-`null` shows the overdue warn banner in the
 *   header, naming the last-checked time (e.g. "07:15") when known.
 * @property showStaleBanner Whether to show the overdue banner at all — a bound,
 *   overdue trigger. Kept separate from [staleSinceLabel] so the banner can show
 *   without a named time (never evaluated yet).
 * @property journalState Rendering branch for the journal section.
 * @property dayGroups The grouped entries when [journalState] is
 *   [TriggerJournalVisualState.Populated].
 */
data class TriggerDetailViewState(
    val name: String,
    val conditionType: TriggerConditionType,
    val conditionLabel: String,
    val pipelineName: String?,
    val enabled: Boolean,
    val showStaleBanner: Boolean = false,
    val staleSinceLabel: String? = null,
    val journalState: TriggerJournalVisualState = TriggerJournalVisualState.Empty,
    val dayGroups: List<TriggerJournalDayGroupUi> = emptyList(),
) {
    /** Whether the trigger is bound to a pipeline (and therefore evaluated). */
    val isBound: Boolean get() = pipelineName != null
}

/**
 * Localised string bundle threaded into `TriggerDetailContent`. Defaults are the
 * final English copy (used by previews / snapshots); the app overrides each with
 * a `stringResource`.
 */
data class TriggerDetailStrings(
    val backCd: String = "Back",
    val subtitle: String = "Trigger detail",
    // Identity header.
    val whenLabel: String = "When",
    val runsLabel: String = "Runs",
    val stateLabel: String = "State",
    val stateEnabled: String = "Enabled",
    val stateDisabled: String = "Disabled",
    val unboundHint: String = "No pipeline yet",
    val unboundAction: String = "No pipeline yet — tap to bind",
    val edit: String = "Edit",
    val delete: String = "Delete",
    // Stale banner.
    val staleBannerTitleFormat: String = "Not checked since %s",
    val staleBannerTitleNoTime: String = "Background checks look overdue",
    val staleBannerBody: String =
        "Background checks look overdue. Some phones pause background work to save battery — " +
            "this trigger may be affected.",
    // Journal section.
    val journalSectionLabel: String = "Evaluation journal",
    val journalWindowLabel: String = "last 30 days",
    val retentionFooter: String = "Records kept for 30 days · older entries age out",
    val emptyTitle: String = "No evaluations yet",
    val emptyBody: String =
        "This trigger hasn’t been checked yet. Each time it’s evaluated — whether it runs or not — " +
            "you’ll see it here.",
    // Sources.
    val sourcePoll: String = "Scheduled check",
    val sourceEvent: String = "Device event",
    val sourceCharging: String = "Charging check",
    // Verdicts.
    val verdictFired: String = "Fired",
    val verdictReArmed: String = "Re-armed",
    val verdictSkipped: String = "Didn’t run",
    val reArmNote: String = "Condition dropped while disarmed — edge latch reset. No run.",
    // Skip-reason sentences (%s = the moment, for condition-not-met).
    val skipDisabled: String = "The trigger was turned off.",
    val skipUnbound: String = "No pipeline is bound.",
    val skipConditionNotMetFormat: String = "The condition wasn’t met at %s.",
    val skipAlreadyFired: String = "It had already fired for this window.",
    // Run outcomes.
    val outcomePending: String = "Running…",
    val outcomeSuccess: String = "Completed",
    val outcomeFailure: String = "Failed",
    val outcomeCancelledBySystem: String = "Stopped by the system",
    val outcomeCancelled: String = "You stopped it",
    val outcomeHitlTimeout: String = "Timed out waiting for approval",
    val outcomeStoppedByCeiling: String = "Stopped by a safety limit",
    // Human-in-the-loop line. The two "parked" qualifiers are appended after the
    // state label when the run had to wait on a notification.
    val hitlWaiting: String = "Waiting for your response",
    val hitlApproved: String = "You approved it",
    val hitlDenied: String = "You denied it",
    val hitlAnswered: String = "You answered it",
    val hitlTimedOut: String = "No response before the window closed",
    val hitlAbandoned: String = "The request never reached you",
    val hitlParkedPending: String = "in the notification shade",
    val hitlParkedSettled: String = "from the notification",
)

/** One-shot callbacks consumed by `TriggerDetailContent`. */
class TriggerDetailCallbacks(
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onToggleEnabled: () -> Unit = {},
    val onBindPipeline: () -> Unit = {},
)

/** Convenience factory returning a no-op detail callback bundle. */
fun noopTriggerDetailCallbacks(): TriggerDetailCallbacks = TriggerDetailCallbacks()
