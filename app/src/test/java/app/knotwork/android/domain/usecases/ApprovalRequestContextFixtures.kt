package app.knotwork.android.domain.usecases

import io.mockk.coEvery
import io.mockk.mockk

/**
 * Stand-ins for [ResolveApprovalRequestContextUseCase] in tests that build the
 * approval gate but are not about what an approval shows.
 */
object ApprovalRequestContextFixtures {

    /**
     * A resolver that finds no request for any run, so every approval is raised
     * as it was before requests were shown. Not a relaxed mock on purpose: that
     * would hand the gate a mocked context, and tests comparing approval states
     * would compare a mock.
     *
     * @return the resolver answering `null` for every run.
     */
    fun none(): ResolveApprovalRequestContextUseCase =
        mockk<ResolveApprovalRequestContextUseCase>().also { coEvery { it(any()) } returns null }
}
