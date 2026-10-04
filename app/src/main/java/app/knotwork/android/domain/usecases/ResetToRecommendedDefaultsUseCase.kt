package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.SettingsReset
import javax.inject.Inject

/**
 * Restores every tunable preference to its recommended default. Backs the
 * "Reset all settings" action inside the Settings → Privacy card.
 *
 * Thin wrapper over [SettingsReset.resetToRecommendedDefaults] so the
 * presentation layer expresses intent through a typed use case (and so the
 * surface is mockable in `SettingsViewModelTest`). The scope guarantees —
 * which preferences are reset and which user data is deliberately left
 * untouched — are documented on the repository method.
 */
class ResetToRecommendedDefaultsUseCase @Inject constructor(private val settingsReset: SettingsReset) {
    /** Writes every tunable preference back to its [SettingsReset]-documented default. */
    suspend operator fun invoke() {
        settingsReset.resetToRecommendedDefaults()
    }
}
