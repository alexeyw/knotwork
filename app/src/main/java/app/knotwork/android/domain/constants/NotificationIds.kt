package app.knotwork.android.domain.constants

/**
 * The one place that decides the Android notification id of everything the app posts.
 *
 * Android keys a notification by its id (the app posts none with a tag): a `notify` on an
 * id that is already showing replaces that notification, and a `cancel` removes whatever
 * holds the id. Two families whose ids overlap therefore replace and remove each other's
 * notifications — including the ongoing notification of a parked run, which is that run's
 * only way back. So every id is either a [fixed][AGENT_FOREGROUND] value or a slot inside
 * its [Family]'s range, and the ranges and fixed values are pairwise disjoint;
 * `NotificationIdsTest` asserts it, and `NotificationIdSourceGuardTest` refuses a posting
 * site that computes an id anywhere else.
 *
 * Lives in `domain/constants` because the data-layer services and the presentation-layer
 * notifiers post side by side; nothing here imports Android.
 */
object NotificationIds {

    /** Ongoing notification of `AgentForegroundService`. */
    const val AGENT_FOREGROUND: Int = 101

    /** Notification of `AgentWorker`'s own foreground promotion. */
    const val AGENT_WORKER_FOREGROUND: Int = 102

    /** Ongoing notification of a model download. */
    const val MODEL_DOWNLOAD: Int = 4711

    /** Every fixed id, for the disjointness check. */
    val FIXED: List<Int> = listOf(AGENT_FOREGROUND, AGENT_WORKER_FOREGROUND, MODEL_DOWNLOAD)

    /**
     * Mask folding a key's hash into a family's range: `hashCode() and SLOT_MASK` is never
     * negative, so a slot is always `base..base + SLOT_MASK` — unlike `hashCode() % n`,
     * which reaches `n - 1` below the base as well as above it.
     */
    const val SLOT_MASK: Int = 0x0FFF

    /**
     * A family of notifications keyed by a string — one slot per key, all slots inside
     * `base..base + SLOT_MASK`.
     *
     * @property base Lowest id of the family's range.
     */
    enum class Family(val base: Int) {
        /** Outcome of a scheduled run, keyed by session. Its range predates this registry and is kept. */
        TASK_RESULT(base = 23_900),

        /** Tool-approval request, keyed by request. */
        APPROVAL(base = 30_000),

        /** Parked clarification question, keyed by session. */
        CLARIFICATION(base = 35_000),

        /** Parked run-ceiling pause, keyed by session. */
        CEILING(base = 40_000),
        ;

        /** Every id this family can produce. */
        val range: IntRange get() = base..base + SLOT_MASK

        /**
         * The slot of [key] in this family.
         *
         * @param key What the family keys its notifications by (a request or session id).
         * @return An id inside [range].
         */
        fun idFor(key: String): Int = base + (key.hashCode() and SLOT_MASK)
    }

    /**
     * Where releases before this registry put the families that have since moved. A
     * notification such a release posted can still be in the shade after the update —
     * an ongoing one of a parked run is not dismissed by the update — and settling its
     * run must remove it. Nothing is ever *posted* here any more.
     *
     * The old formula was `base + key.hashCode() % 1000`: its slots reached 999 below
     * the base, overlapped each other and the fixed ids, so a notification found in
     * one of them is only taken for its family's if it also sits on that family's
     * channel (see the notifiers).
     */
    object PreUpdate {
        /** Old base of the approval family, keyed by session then. */
        const val APPROVAL_BASE: Int = 201

        /** Old base of the clarification family. */
        const val CLARIFICATION_BASE: Int = 301

        /** Old base of the ceiling family. */
        const val CEILING_BASE: Int = 401

        /** Size of the old `hashCode() % RANGE` fold. */
        private const val RANGE: Int = 1_000

        /**
         * The slot [key] occupied under the old formula.
         *
         * @param base One of the old bases above.
         * @param key The session id the old release keyed the notification by.
         * @return The id that release used.
         */
        fun slot(base: Int, key: String): Int = base + key.hashCode() % RANGE
    }
}
