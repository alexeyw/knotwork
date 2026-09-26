package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DexInvocationChecker], over `dexdump -d` text shaped like the
 * release artefact's (lines copied from a real disassembly, 26.09.2026).
 */
class DexInvocationCheckerTest {

    private val disassembly = listOf(
        "Class #7  -",
        "  Class descriptor  : '$LOGGER;'",
        "1f7ff4: 7020 c52e 4000        |0040: invoke-direct {v0, v4}, $REMOTE;.<init>:(Landroid/content/Context;)V",
        "1f8010: 7220 c62e 1000        |0026: invoke-interface {v0, v1}, $CLIENT;.logEvent:(L$PROTO;)V // method@2ec6",
        "  name          : 'logEvent'",
    ).joinToString("\n")

    @Test
    fun `given an invoke of the method when checked then exactly its line is reported`() {
        val found = disassembly.lines().filter { DexInvocationChecker.isInvocation(it, CLIENT, "logEvent") }

        assertEquals(1, found.size)
        assertTrue(found.single().contains("invoke-interface"))
    }

    @Test
    fun `given a declaration or a constructor call when checked then neither is an invocation of the method`() {
        val lines = disassembly.lines().filterNot { "invoke-interface" in it }

        assertTrue(lines.none { DexInvocationChecker.isInvocation(it, CLIENT, "logEvent") })
        assertTrue(lines.none { DexInvocationChecker.isInvocation(it, REMOTE, "logEvent") })
    }

    @Test
    fun `given a disassembly when a class is looked up then only a defined one is found`() {
        assertTrue(disassembly.lines().any { DexInvocationChecker.isClassDefinition(it, LOGGER) })
        assertFalse(disassembly.lines().any { DexInvocationChecker.isClassDefinition(it, "$PACKAGE/Missing") })
    }

    private companion object {
        const val PACKAGE = "Lcom/google/mediapipe/tasks/core/logging"
        const val LOGGER = "$PACKAGE/TasksStatsProtoLogger"
        const val CLIENT = "$PACKAGE/LoggingClient"
        const val REMOTE = "$PACKAGE/RemoteLoggingClient"
        const val PROTO = "com/google/mediapipe/proto/MediaPipeLoggingProto${'$'}MediaPipeLogExtension"
    }
}
