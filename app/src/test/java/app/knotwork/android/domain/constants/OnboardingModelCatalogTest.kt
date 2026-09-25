package app.knotwork.android.domain.constants

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [OnboardingModelCatalog].
 *
 * The catalog is two preset entries looked up by id; the `Custom URL…` row's link
 * is checked by `CustomModelLink` (see `CustomModelLinkTest`). The presets are
 * exercised end-to-end via [OnboardingViewModelTest].
 */
class OnboardingModelCatalogTest {

    @Test
    fun `entryById resolves bundled preset ids`() {
        val gemma2 = OnboardingModelCatalog.entryById(OnboardingModelCatalog.ID_GEMMA_4_E2B)

        assertEquals("gemma-4-E2B-it.litertlm", gemma2?.fileName)
    }

    @Test
    fun `entryById returns null for the custom URL row`() {
        // The Custom URL path has no preset URL — the VM falls back to
        // the user-supplied `customDownloadUrl` field instead.
        assertEquals(null, OnboardingModelCatalog.entryById(OnboardingModelCatalog.ID_CUSTOM_URL))
    }
}
