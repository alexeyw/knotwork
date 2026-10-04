package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.isInteractive
import app.knotwork.android.domain.models.RunCeilingLimit
import app.knotwork.android.domain.models.RunCeilings
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Resolves the ceilings in force for one run, once, at the top of its tree.
 *
 * The one decision this use case makes is *which* configured number applies,
 * and it makes it from the run's [RunOrigin]: an interactive run has a person
 * in front of it who can see it misbehaving and stop it, and a background run
 * — a trigger firing overnight against the user's own cloud key — does not.
 * That is the whole reason background origins get their own, tighter numbers.
 *
 * The partition itself is **not** re-declared here. It reuses
 * `RunOrigin.isInteractive`, whose `when` is deliberately exhaustive so a new
 * origin cannot compile until somebody classifies it. Restating the partition
 * in a second place would be exactly the drift that guard exists to prevent:
 * a new background surface would be classified once, correctly, and once here,
 * by omission, as interactive.
 *
 * Following the `RunRateCeiling` precedent, the resolved value object carries
 * the limits and nothing else — the counting belongs to
 * [app.knotwork.android.domain.models.RunBudgetLedger], which knows what it is
 * counting.
 *
 * @property runSettings Source of the four configured ceilings.
 */
class ResolveRunCeilingsUseCase @Inject constructor(private val runSettings: RunSettings) {

    /**
     * Resolves the ceilings for a run of the given origin.
     *
     * @param origin What started the run tree.
     * @return The limits every node in the tree is charged against. The money
     *   axis is [RunCeilingLimit.Unavailable] in this release — the app is
     *   bring-your-own-key and has no price source, and a ceiling that claims
     *   to bound spend while measuring nothing is worse than none.
     */
    suspend operator fun invoke(origin: RunOrigin): RunCeilings {
        val background = !origin.isInteractive
        val hardSteps = if (background) {
            runSettings.pipelineMaxStepsBackground.first()
        } else {
            runSettings.pipelineMaxSteps.first()
        }
        val hardTokens = if (background) {
            runSettings.runMaxTokensBackground.first()
        } else {
            runSettings.runMaxTokens.first()
        }
        return RunCeilings(
            origin = origin,
            steps = RunCeilingLimit.Enforced(soft = RunCeilings.softFor(hardSteps), hard = hardSteps),
            tokens = RunCeilingLimit.Enforced(soft = RunCeilings.softFor(hardTokens), hard = hardTokens),
            money = RunCeilingLimit.Unavailable,
        )
    }
}
