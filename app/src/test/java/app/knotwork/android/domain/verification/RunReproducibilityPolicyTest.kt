package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.verification.VerificationFixtures.HEADER
import app.knotwork.android.domain.verification.VerificationFixtures.localCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunReproducibilityPolicy] promises only what was measured on the reference
 * device: CPU and GPU (GPU at the same window) on the same device; not the NPU,
 * not an image, not a cloud model, not a run recorded before seeds.
 */
class RunReproducibilityPolicyTest {

    @Test
    fun `given calls on CPU and GPU when judged then both can be repeated`() {
        assertNull(RunReproducibilityPolicy.notVerifiable(localCall(0, "a", backend = LocalBackend.CPU)))
        assertNull(RunReproducibilityPolicy.notVerifiable(localCall(0, "a", backend = LocalBackend.GPU)))
    }

    @Test
    fun `given a call on the NPU, with an image or without its model when judged then it cannot`() {
        assertEquals(
            NotVerifiableReason.NPU,
            RunReproducibilityPolicy.notVerifiable(localCall(0, "a", backend = LocalBackend.NPU)),
        )
        assertEquals(
            NotVerifiableReason.IMAGE_INPUT,
            RunReproducibilityPolicy.notVerifiable(localCall(0, "a", hadImage = true)),
        )
        assertEquals(
            NotVerifiableReason.MODEL_NOT_RECORDED,
            RunReproducibilityPolicy.notVerifiable(localCall(0, "a", modelSha = null)),
        )
        assertEquals(
            NotVerifiableReason.MODEL_NOT_RECORDED,
            RunReproducibilityPolicy.notVerifiable(localCall(0, "a", window = null)),
        )
    }

    @Test
    fun `given a GPU call when asked about the window then it matters, on CPU it does not`() {
        assertTrue(RunReproducibilityPolicy.windowMatters(localCall(0, "a", backend = LocalBackend.GPU)))
        assertFalse(RunReproducibilityPolicy.windowMatters(localCall(0, "a", backend = LocalBackend.CPU)))
    }

    @Test
    fun `given each kind of run when the promise is stated then it names the one reason`() {
        val cpu = localCall(0, "a")
        val gpu = localCall(1, "b", backend = LocalBackend.GPU)

        assertEquals(
            Reproducibility.Promised(setOf(LocalBackend.CPU, LocalBackend.GPU), usedCloud = true),
            RunReproducibilityPolicy.promise(HEADER, listOf(cpu, gpu), cloudCalls = 1),
        )
        assertEquals(
            Reproducibility.NotPromised(NotPromisedReason.PRE_VERSION),
            RunReproducibilityPolicy.promise(null, listOf(cpu), cloudCalls = 0),
        )
        assertEquals(
            Reproducibility.NotPromised(NotPromisedReason.CLOUD_ONLY),
            RunReproducibilityPolicy.promise(HEADER, emptyList(), cloudCalls = 2),
        )
        assertEquals(
            Reproducibility.NotPromised(NotPromisedReason.NPU),
            RunReproducibilityPolicy.promise(HEADER, listOf(cpu, localCall(2, "c", backend = LocalBackend.NPU)), 0),
        )
        assertEquals(
            Reproducibility.NotPromised(NotPromisedReason.IMAGE),
            RunReproducibilityPolicy.promise(HEADER, listOf(localCall(0, "a", hadImage = true)), 0),
        )
    }
}
