package app.knotwork.android.domain.services

import app.knotwork.android.domain.repositories.MemorySettings
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the currently active [EmbeddingProvider] from the Hilt-registered
 * provider map and the user's persisted selection.
 *
 * The memory pipeline asks this resolver for "the" embedding provider on every
 * embed/search call rather than holding a fixed provider reference, so that a
 * mid-session change to `MemorySettings.activeEmbeddingProviderId` takes
 * effect immediately without restarting any long-lived component.
 *
 * @property providers All registered providers, keyed by [EmbeddingProvider.id]
 *   (supplied by Hilt multibinding). Always contains at least the on-device
 *   [EmbeddingProvider.ID_USE] entry.
 * @property memorySettings Source of the active provider id.
 */
@Singleton
class EmbeddingProviderResolver @Inject constructor(
    private val providers: Map<String, @JvmSuppressWildcards EmbeddingProvider>,
    private val memorySettings: MemorySettings,
) {

    /**
     * Returns the provider that should serve the current request.
     *
     * Falls back to the always-present on-device default
     * ([EmbeddingProvider.ID_USE]) — logging the reason — in two cases, so
     * memory operations degrade gracefully instead of crashing or returning
     * mis-dimensioned vectors:
     *  - the persisted id does not match any registered provider (e.g. a
     *    provider was removed in an app update, or the value is stale);
     *  - the selected provider is currently unavailable
     *    ([EmbeddingProvider.isAvailable] is `false`, e.g. a cloud provider
     *    with no API key configured). Performing the fallback here — rather
     *    than inside the provider — keeps each provider's declared
     *    [EmbeddingProvider.dimension] honest: the returned provider always
     *    produces vectors of its own dimension.
     *
     * @return The provider to use; never null.
     */
    suspend fun resolve(): EmbeddingProvider {
        val activeId = memorySettings.activeEmbeddingProviderId.first()
        val active = providers[activeId]
        if (active == null) {
            Timber.w(
                "Unknown embedding provider id '%s'; falling back to '%s'",
                activeId,
                EmbeddingProvider.ID_USE,
            )
            return providers.getValue(EmbeddingProvider.ID_USE)
        }
        if (!active.isAvailable()) {
            Timber.i(
                "Embedding provider '%s' is unavailable; falling back to '%s'",
                activeId,
                EmbeddingProvider.ID_USE,
            )
            return providers.getValue(EmbeddingProvider.ID_USE)
        }
        return active
    }
}
