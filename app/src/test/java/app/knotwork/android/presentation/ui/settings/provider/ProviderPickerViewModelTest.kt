package app.knotwork.android.presentation.ui.settings.provider

import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.repositories.ApiKeyRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Unit tests for [ProviderPickerViewModel] — which providers the picker marks *added*. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderPickerViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `given a key or an address saved then the provider is added, a missing model notwithstanding`() = runTest {
        val repository = mockk<ApiKeyRepository> {
            every { getApiKey(any()) } answers {
                flowOf(if (firstArg<CloudProvider>() == CloudProvider.OPENROUTER) "sk-or" else " ")
            }
            every { getBaseUrl(any()) } answers {
                flowOf(if (firstArg<CloudProvider>() == CloudProvider.OPENAI_COMPATIBLE) "http://10.0.0.2/v1" else null)
            }
        }
        val viewModel = ProviderPickerViewModel(repository)
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { viewModel.added.collect {} }

        advanceUntilIdle()

        assertEquals(setOf(ProviderId.OpenRouter, ProviderId.OpenAiCompatible), viewModel.added.value)
    }
}
