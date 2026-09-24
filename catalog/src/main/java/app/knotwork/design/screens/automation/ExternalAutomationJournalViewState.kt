package app.knotwork.design.screens.automation

/**
 * What the app decided about one inbound external-automation request — the
 * catalog mirror of the domain `ExternalAutomationStatus`.
 *
 * Flattened from the domain's sealed hierarchy into an enum plus a separate
 * nullable reason on the row, because the catalog renders a status and a reason
 * as two independent visual elements (the timeline tile and the second line) and
 * nesting the reason inside two of five values would only make every call site
 * unwrap it again.
 */
enum class ExternalRequestStatusUi {
    /** Admitted; a background run was enqueued and has not settled yet. */
    Accepted,

    /** The run the request started finished successfully. */
    Completed,

    /** The run the request started failed, was cancelled, or was interrupted. */
    Failed,

    /**
     * Refused before anything started, because of what the request said or how the
     * app is configured. Retrying it unchanged yields the identical refusal.
     */
    Rejected,

    /**
     * Well-formed and permitted, refused **at this moment** by a safety ceiling.
     * The same request may be accepted later.
     */
    Blocked,
}

/**
 * Why a request was not executed — the catalog mirror of the domain
 * `ExternalAutomationRejectionReason`. The presentation layer renders each as a
 * human sentence, never the constant name.
 */
enum class ExternalRequestReasonUi {
    /** External automation is switched off (its default state). */
    ContractDisabled,

    /** Switched on, but no pipeline is picked for outside apps to run. */
    SurfaceNotBound,

    /** The caller named a pipeline other than the picked one. */
    TargetNotAllowed,

    /** The caller named no pipeline at all. */
    TargetMissing,

    /** The caller named the pipeline by id *and* by name, irreconcilably. */
    TargetAmbiguous,

    /** The caller used an action this contract does not define. */
    UnknownAction,

    /** The caller sent no message for the pipeline to run on. */
    PromptMissing,

    /** The caller sent the message twice, plain and base64. */
    PromptAmbiguous,

    /** The base64 message would not decode. */
    PromptUndecodable,

    /** The caller sent no request id to correlate the answer with. */
    RequestIdMissing,

    /** The caller's request id or answer address is longer than the contract allows. */
    ValueTooLong,

    /** Over the hourly ceiling on accepted external requests. */
    RateLimited,

    /**
     * The caller asked for its answer to be delivered to a package other than the
     * one the system reported as the sender — the one reason that describes an
     * attempt to make this app broadcast at a third party, and the only one the
     * row treats as a security event rather than a misconfiguration.
     */
    ReturnPackageMismatch,
}

/**
 * How a row names the app that sent the request, and on what authority — the
 * distinction the journal exists to keep visible.
 *
 * A `BroadcastReceiver` learns the sending package only when the **sender** opted
 * into sharing its identity, which defaults to off and which no automation app
 * sets. So the ordinary row has nothing but the caller's own claim about where to
 * send the answer, and rendering that claim the same way as a system-attested
 * name would quietly promote it into an identity.
 */
enum class ExternalRequestSenderKindUi {
    /** The system reported the sending package; the name can be trusted. */
    Attested,

    /** Only the caller's own claim about which app to answer — unverified. */
    Claimed,
}

/**
 * One inbound request projected for display. Structured status / reason carry the
 * glyph and the wording; every dynamic string (timestamp, target, package,
 * request id) is pre-resolved by the app so the catalog neither formats time nor
 * holds locale.
 *
 * @property id Stable journal-row id (list key).
 * @property status What the app decided.
 * @property reason The typed refusal reason for a [ExternalRequestStatusUi.Rejected]
 *   or [ExternalRequestStatusUi.Blocked] row; `null` otherwise.
 * @property targetLabel Pre-resolved description of the pipeline the caller asked
 *   for (e.g. `Morning digest` or `id 8f21c4…`), or `null` when it named none —
 *   which is itself a refusal reason rather than a fallback.
 * @property actionLabel The action the request arrived with, verbatim. Rendered
 *   only when [showAction] is set, so a typo in a caller's profile is visible
 *   without putting the same constant on every ordinary row.
 * @property showAction Whether to render [actionLabel]; the app sets it for a
 *   request whose action is not the contract's own.
 * @property senderLabel Package name to show on the provenance line, or `null` to
 *   omit the line (a fire-and-forget call with no attested sender).
 * @property senderKind Whether [senderLabel] is system-attested or merely claimed.
 * @property requestIdLabel The caller-minted correlation id, already truncated by
 *   the app; `null` to omit it. It is what ties a row to the caller's own log.
 * @property timestampLabel Pre-resolved moment label ("just now" / "12m ago" /
 *   "07:15").
 * @property repeatCount How many identical consecutive refusals this row stands
 *   for. `1` renders no badge — a looping caller must read as one recurring
 *   problem, not as forty-three separate incidents.
 */
data class ExternalRequestEntryUi(
    val id: String,
    val status: ExternalRequestStatusUi,
    val reason: ExternalRequestReasonUi? = null,
    val targetLabel: String? = null,
    val actionLabel: String = "",
    val showAction: Boolean = false,
    val senderLabel: String? = null,
    val senderKind: ExternalRequestSenderKindUi = ExternalRequestSenderKindUi.Claimed,
    val requestIdLabel: String? = null,
    val timestampLabel: String,
    val repeatCount: Int = 1,
)

/**
 * A contiguous run of requests received on the same device-local day.
 *
 * @property headerLabel Pre-resolved day label ("Today" / "Yesterday" /
 *   "Mon 14 Jul").
 * @property entries The entries, newest first.
 */
data class ExternalRequestDayGroupUi(val headerLabel: String, val entries: List<ExternalRequestEntryUi>)

/** Rendering branch of the request timeline. */
enum class ExternalJournalVisualState {
    /** First read from the encrypted store — skeleton rows. */
    Loading,

    /** Nothing has ever arrived — the teaching empty state. */
    Empty,

    /** One or more day-grouped entries. */
    Populated,
}

/**
 * One key of the wire contract, shown in the "how another app calls this" block.
 *
 * Carries no required/optional tag. No key of this contract is unconditionally
 * required — a target and a prompt are each required in one of two mutually
 * exclusive forms, and the correlation id only when the caller asked to be
 * answered — so a per-key flag could only ever have printed "optional" beside
 * keys whose absence is a refusal. Each condition is stated in [meaning], which
 * is the only place able to express it.
 *
 * @property key The extra key exactly as it travels on the wire.
 * @property meaning One-line human description, including when the key is needed.
 */
data class ExternalCallKeyUi(val key: String, val meaning: String)

/**
 * Immutable input to `ExternalAutomationJournalContent` — the contract's current
 * posture above the log of every request that has reached it.
 *
 * The posture is on the screen and not only on the settings row because a journal
 * full of refusals is unreadable without it: "refused because the contract is off"
 * is a different problem from "refused because your profile names the wrong
 * pipeline", and the banner is what tells the two apart at a glance.
 *
 * @property contractEnabled Whether the entry point currently accepts anything.
 * @property boundPipelineName Name of the one pipeline outside apps may run, or
 *   `null` when nothing is bound — a real, reachable, inert state, not an
 *   absence to hide.
 * @property journalState Rendering branch for the timeline.
 * @property dayGroups The grouped entries when [journalState] is
 *   [ExternalJournalVisualState.Populated].
 * @property callAction The broadcast action a caller sends, from the contract
 *   source of truth.
 * @property callKeys The contract's request keys, in wire order.
 * @property callBlockInitiallyExpanded Whether the "how another app calls this"
 *   block starts open. Collapsed by default; the flag exists so a snapshot can
 *   capture the open state deterministically.
 */
data class ExternalAutomationJournalViewState(
    val contractEnabled: Boolean,
    val boundPipelineName: String?,
    val journalState: ExternalJournalVisualState = ExternalJournalVisualState.Empty,
    val dayGroups: List<ExternalRequestDayGroupUi> = emptyList(),
    val callAction: String = "",
    val callKeys: List<ExternalCallKeyUi> = emptyList(),
    val callBlockInitiallyExpanded: Boolean = false,
) {
    /** Whether a pipeline is picked, and the surface can therefore run anything. */
    val isBound: Boolean get() = boundPipelineName != null
}

/**
 * Localised string bundle threaded into `ExternalAutomationJournalContent`.
 * Defaults are the final English copy (used by previews / snapshots); the app
 * overrides each with a `stringResource`.
 *
 * The whole external-entry vocabulary lives here in one place on purpose: five
 * statuses and twelve reasons are the screen's entire reason to exist, and a
 * reader auditing the wording should find all of it in one list rather than
 * spread across the composables that happen to render each piece.
 */
@Suppress("LongParameterList") // Documented public copy surface; folding hides the request vocabulary.
data class ExternalAutomationJournalStrings(
    val title: String = "External automation",
    val subtitle: String = "Request journal",
    val backCd: String = "Back",
    // Posture banners.
    val bannerOffTitle: String = "Switched off",
    val bannerOffBody: String =
        "No outside app can start a run. Requests that arrive anyway are refused on the spot — " +
            "and still recorded below, so a profile calling a switched-off contract stays visible.",
    val bannerUnboundTitle: String = "No pipeline picked",
    val bannerUnboundBody: String =
        "External automation is on, but nothing is picked for outside apps to run, so every request " +
            "is refused. Pick a pipeline on the previous screen to make it work.",
    val bannerBoundTitle: String = "Accepting requests",
    val bannerBoundBodyFormat: String = "Outside apps may run one pipeline: %s. Nothing else.",
    // Section chrome.
    val sectionLabel: String = "Inbound requests",
    val retentionFooter: String = "Requests are kept for 30 days · at most 2,000 are stored",
    val emptyTitle: String = "No requests yet",
    val emptyBody: String =
        "Nothing has asked to run a pipeline yet. Every request another app sends — accepted or " +
            "refused — shows up here.",
    // Statuses.
    val statusAccepted: String = "Accepted",
    val statusCompleted: String = "Completed",
    val statusFailed: String = "Failed",
    val statusRejected: String = "Refused",
    val statusBlocked: String = "Held back",
    // Second line for the three run-bearing statuses.
    val outcomeRunning: String = "Running…",
    val outcomeCompleted: String = "The run finished.",
    val outcomeFailed: String = "The run didn’t finish.",
    // The distinction the two refusal statuses exist to draw.
    val rejectedHint: String = "Sending the same request again gives the same answer.",
    val blockedHint: String = "The same request can be accepted later.",
    // Reason sentences.
    val reasonContractDisabled: String = "External automation is switched off.",
    val reasonSurfaceNotBound: String = "No pipeline is picked for outside apps to run.",
    val reasonTargetNotAllowed: String = "It asked for a pipeline other than the one you picked.",
    val reasonTargetMissing: String = "It didn’t say which pipeline to run.",
    val reasonTargetAmbiguous: String = "It named the pipeline twice, by id and by name, and the two disagree.",
    val reasonUnknownAction: String = "It used an action this app doesn’t answer.",
    val reasonPromptMissing: String = "It didn’t include a message to run on.",
    val reasonPromptAmbiguous: String = "It sent the message twice, as plain text and as base64.",
    val reasonPromptUndecodable: String = "The base64 message couldn’t be decoded.",
    val reasonRequestIdMissing: String = "It didn’t include a request id to answer with.",
    val reasonValueTooLong: String = "It sent an id or an answer address longer than the contract allows.",
    val reasonRateLimited: String = "Too many requests in the last hour.",
    val reasonReturnPackageMismatch: String =
        "It asked for the answer to be sent to a different app than the one that called.",
    val reasonReturnPackageMismatchNote: String = "Nothing was run, and nothing was sent to that app.",
    // Row detail lines.
    val targetPrefix: String = "for",
    val actionPrefixFormat: String = "action %s",
    val senderAttestedFormat: String = "sent by %s · confirmed by Android",
    val senderClaimedFormat: String = "answer requested to %s · unverified",
    val requestIdFormat: String = "req %s",
    val repeatFormat: String = "×%d",
    val repeatCdFormat: String = "repeated %d times",
    // "How another app calls this" block.
    val callBlockTitle: String = "How another app calls this",
    val callBlockBody: String =
        "Send a broadcast with this action and these extras. Worked Tasker, MacroDroid and adb " +
            "examples live in the project documentation.",
    val callBlockActionLabel: String = "Action",
    val callBlockKeysLabel: String = "Extras",
    val callBlockCopy: String = "Copy",
    val callBlockExpandCd: String = "Show how to call this",
    // Journal export.
    val exportShareCd: String = "Share the request journal",
    val exportSaveCd: String = "Save the request journal",
)

/** One-shot callbacks consumed by `ExternalAutomationJournalContent`. */
class ExternalAutomationJournalCallbacks(
    val onBack: () -> Unit = {},
    val onCopyCallDetails: () -> Unit = {},
    val onShareJournal: () -> Unit = {},
    val onSaveJournal: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopExternalAutomationJournalCallbacks(): ExternalAutomationJournalCallbacks = ExternalAutomationJournalCallbacks()
