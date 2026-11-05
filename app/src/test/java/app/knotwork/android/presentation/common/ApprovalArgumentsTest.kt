package app.knotwork.android.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arguments of a call waiting for approval as the card and the notification show them:
 * laid out, never respelled, shown as they came when they are not JSON.
 */
class ApprovalArgumentsTest {

    @Test
    fun `given arguments when shown on the card then they are indented and every token is as sent`() {
        val sent = """{"path":"/Users/me/a.md","n":1.50,"big":12345678901234567890123,"meta":{"tags":["a"]}}"""

        assertEquals(
            """
            {
              "path": "/Users/me/a.md",
              "n": 1.50,
              "big": 12345678901234567890123,
              "meta": {
                "tags": [
                  "a"
                ]
              }
            }
            """.trimIndent(),
            ApprovalArguments.forCard(sent),
        )
    }

    @Test
    fun `given arguments when shown in the notification then they are on one line`() {
        assertEquals(
            """{"path": "/Users/me/a.md", "meta": {"tags": ["a"]}}""",
            ApprovalArguments.forNotification("{\n  \"path\": \"/Users/me/a.md\",\n  \"meta\": {\"tags\": [\"a\"]}\n}"),
        )
    }

    @Test
    fun `given arguments that are not JSON when shown then both surfaces show them as they came`() {
        // org.json would run this (it reads unquoted keys, single quotes and a trailing comma);
        // the card shows the text it would run with, not a guess at what was meant.
        val notJson = "{path: '/var/log',}"

        assertEquals(notJson, ApprovalArguments.forCard(notJson))
        assertEquals(notJson, ApprovalArguments.forNotification(notJson))
    }

    @Test
    fun `given no arguments when shown then both surfaces show an empty object`() {
        for (blank in listOf("", "  \n ")) {
            assertEquals("{}", ApprovalArguments.forCard(blank))
            assertEquals("{}", ApprovalArguments.forNotification(blank))
        }
    }

    @Test
    fun `given whitespace around the arguments when shown then it is dropped, valid or not`() {
        assertEquals("{\n  \"a\": 1\n}", ApprovalArguments.forCard("\n  {\"a\":1}  \n"))
        assertEquals("not json", ApprovalArguments.forCard("\n not json \n"))
    }
}
