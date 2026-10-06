package app.knotwork.design.components.pipelineeditor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [NodeType.text] maps every node type to its own name and description.
 *
 * The `when` is exhaustive, so a missing type does not compile; what the
 * compiler cannot see is a copy-paste slip that points two types at the same
 * resource, or a type at another type's text. Both would show the wrong name
 * in the picker while the generated documents — which key the file by
 * resource name — stayed right.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class NodeTypeTextTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `given every node type when resolved then each has its own non-blank name and description`() {
        val resolved = NodeType.entries.associateWith { type ->
            context.getString(type.text.name) to context.getString(type.text.description)
        }

        resolved.forEach { (type, texts) ->
            assertTrue("$type has a blank name.", texts.first.isNotBlank())
            assertTrue("$type has a blank description.", texts.second.isNotBlank())
        }
        assertEquals("Two types share a name.", NodeType.entries.size, resolved.values.map { it.first }.toSet().size)
        assertEquals(
            "Two types share a description.",
            NodeType.entries.size,
            resolved.values.map { it.second }.toSet().size,
        )
    }

    @Test
    fun `given every node type when mapped then it points at the resources named after it`() {
        NodeType.entries.forEach { type ->
            val prefix = "knotwork_node_type_${type.name.lowercase()}"
            assertEquals(type.name, "${prefix}_name", context.resources.getResourceEntryName(type.text.name))
            assertEquals(
                type.name,
                "${prefix}_description",
                context.resources.getResourceEntryName(type.text.description),
            )
        }
    }
}
