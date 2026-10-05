package app.knotwork.android.data.prompt

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DeviceVariableProvider]: the `$DEVICE` descriptor, whose format a run header
 * shares through [DeviceVariableProvider.describe].
 */
class DeviceVariableProviderTest {

    @Test
    fun `given every part when described then maker, model and Android version are joined`() {
        assertEquals("Samsung SM-S938B · Android 16", DeviceVariableProvider.describe("samsung", "SM-S938B", "16"))
    }

    @Test
    fun `given blank parts when described then they are dropped, and nothing left reads as unknown`() {
        assertEquals("SM-S938B", DeviceVariableProvider.describe(" ", "SM-S938B", ""))
        assertEquals("Android 16", DeviceVariableProvider.describe("", "", "16"))
        assertEquals("unknown device", DeviceVariableProvider.describe("", " ", ""))
    }

    @Test
    fun `given the provider when resolved then it renders the same descriptor`() = runTest {
        val provider = DeviceVariableProvider({ "google" }, { "Pixel 9" }, { "15" })

        assertEquals(DeviceVariableProvider.describe("google", "Pixel 9", "15"), provider.resolve())
        assertEquals("Google Pixel 9 · Android 15", provider.resolve())
    }
}
