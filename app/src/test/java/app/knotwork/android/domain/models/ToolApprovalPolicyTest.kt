package app.knotwork.android.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPolicyTest {

    @Test
    fun `fromKey resolves wire keys`() {
        assertEquals(ToolApprovalPolicy.AllCalls, ToolApprovalPolicy.fromKey("all_calls"))
        assertEquals(ToolApprovalPolicy.SensitiveOrDestructive, ToolApprovalPolicy.fromKey("sensitive_or_destructive"))
        assertEquals(ToolApprovalPolicy.NeverPrompt, ToolApprovalPolicy.fromKey("never_prompt"))
    }

    @Test
    fun `fromKey returns DEFAULT for unknown values`() {
        assertEquals(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicy.fromKey("bogus"))
        assertEquals(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicy.fromKey(null))
    }

    @Test
    fun `DEFAULT is SensitiveOrDestructive`() {
        assertEquals(ToolApprovalPolicy.SensitiveOrDestructive, ToolApprovalPolicy.DEFAULT)
    }

    @Test
    fun `given any policy when a destructive call is due then it requires approval`() {
        // The floor of the rule, over every policy that exists — including one
        // added later. Quieting a destructive call is not a policy's to decide:
        // the destructive block refuses it instead.
        for (policy in ToolApprovalPolicy.entries) {
            assertTrue("$policy let a DESTRUCTIVE call run unasked", policy.requiresApproval(ToolRisk.DESTRUCTIVE))
        }
    }

    @Test
    fun `given AllCalls when any call is due then it requires approval`() {
        for (risk in ToolRisk.entries) {
            assertTrue("AllCalls let $risk through", ToolApprovalPolicy.AllCalls.requiresApproval(risk))
        }
    }

    @Test
    fun `given SensitiveOrDestructive when a call is due then only read-only runs unasked`() {
        assertFalse(ToolApprovalPolicy.SensitiveOrDestructive.requiresApproval(ToolRisk.READ_ONLY))
        assertTrue(ToolApprovalPolicy.SensitiveOrDestructive.requiresApproval(ToolRisk.SENSITIVE))
        assertTrue(ToolApprovalPolicy.SensitiveOrDestructive.requiresApproval(ToolRisk.DESTRUCTIVE))
    }

    @Test
    fun `given NeverPrompt when a call is due then only destructive asks`() {
        assertFalse(ToolApprovalPolicy.NeverPrompt.requiresApproval(ToolRisk.READ_ONLY))
        assertFalse(ToolApprovalPolicy.NeverPrompt.requiresApproval(ToolRisk.SENSITIVE))
        assertTrue(ToolApprovalPolicy.NeverPrompt.requiresApproval(ToolRisk.DESTRUCTIVE))
    }
}
