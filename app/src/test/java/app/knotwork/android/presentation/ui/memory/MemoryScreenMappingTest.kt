package app.knotwork.android.presentation.ui.memory

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemoryPendingUpdate
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryVersion
import app.knotwork.design.screens.memory.MemoryCategory
import app.knotwork.design.screens.memory.MemoryDateFilter
import app.knotwork.design.screens.memory.MemoryPairRole
import app.knotwork.design.screens.memory.MemorySourceKind
import app.knotwork.design.screens.memory.MemoryVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [toViewState] — the pure projection from [MemoryUiState] to the
 * catalog `MemoryViewState` (filtering, grouping, breakdown, detail labels).
 */
class MemoryScreenMappingTest {

    private val now = 1_000_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun chunk(
        id: Long,
        ageDays: Long = 0,
        pinned: Boolean = false,
        source: MemorySource = MemorySource.Manual,
        text: String = "chunk $id",
        useCount: Int = 0,
        lastUsedAt: Long? = null,
        tags: List<String> = emptyList(),
    ) = MemoryChunk(
        id = id,
        text = text,
        embedding = floatArrayOf(0f),
        timestamp = now - ageDays * dayMs,
        isPinned = pinned,
        source = source,
        tags = tags,
        useCount = useCount,
        lastUsedAt = lastUsedAt,
    )

    @Test
    fun `empty memories yields Empty visual state`() {
        val vs = MemoryUiState().toViewState(now)
        assertEquals(MemoryVisualState.Empty, vs.visualState)
    }

    @Test
    fun `header breakdown counts sources and formats percentages`() {
        val state = MemoryUiState(
            memories = listOf(
                chunk(1, source = MemorySource.ChatSession("s")),
                chunk(2, source = MemorySource.ChatSession("s")),
                chunk(3, source = MemorySource.Manual),
                chunk(4, source = MemorySource.Compaction(emptyList())),
            ),
            totalBytes = 2_100_000L,
        )
        val vs = state.toViewState(now)
        assertEquals("4", vs.header.totalLabel)
        assertTrue(vs.header.sizeLabel.endsWith("MB"))
        // Auto 2/4 = 50%, Compact 1/4 = 25%, Manual 1/4 = 25%.
        val auto = vs.header.segments.first { it.kind == MemorySourceKind.Auto }
        assertEquals("AUTO 50 %", auto.label)
    }

    @Test
    fun `category chips carry per-source counts`() {
        val state = MemoryUiState(
            memories = listOf(
                chunk(1, pinned = true, source = MemorySource.Manual),
                chunk(2, source = MemorySource.ChatSession("s")),
            ),
        )
        val chips = state.toViewState(now).categoryChips.associate { it.category to it.count }
        assertEquals(2, chips[MemoryCategory.All])
        assertEquals(1, chips[MemoryCategory.Pinned])
        assertEquals(1, chips[MemoryCategory.Auto])
        assertEquals(1, chips[MemoryCategory.Manual])
    }

    @Test
    fun `Manual category filter keeps only manual chunks`() {
        val state = MemoryUiState(
            memories = listOf(chunk(1, source = MemorySource.Manual), chunk(2, source = MemorySource.ChatSession("s"))),
            selectedCategory = MemoryCategory.Manual,
        )
        val ids = state.toViewState(now).sections.flatMap { it.rows }.map { it.id }
        assertEquals(listOf("1"), ids)
    }

    @Test
    fun `Last7Days date filter drops older chunks`() {
        val state = MemoryUiState(
            memories = listOf(chunk(1, ageDays = 2), chunk(2, ageDays = 10)),
            dateFilter = MemoryDateFilter.Last7Days,
        )
        val ids = state.toViewState(now).sections.flatMap { it.rows }.map { it.id }
        assertEquals(listOf("1"), ids)
    }

    @Test
    fun `rows are grouped into Pinned Today and This week sections`() {
        val state = MemoryUiState(
            memories = listOf(
                chunk(1, pinned = true, ageDays = 0),
                chunk(2, ageDays = 0),
                chunk(3, ageDays = 3),
            ),
        )
        val titles = state.toViewState(now).sections.map { it.title }
        assertEquals(listOf("Pinned", "Today", "This week"), titles)
    }

    @Test
    fun `detail maps token count learned-from and used-in`() {
        val state = MemoryUiState(
            memories = listOf(
                chunk(
                    1,
                    source = MemorySource.ChatSession("s1"),
                    text = "12345678",
                    useCount = 6,
                    lastUsedAt = now - 2 * 60 * 60 * 1000,
                    tags = listOf("knowledge"),
                ),
            ),
            sessionNames = mapOf("s1" to "Pixel 9 NPU setup"),
            expandedId = 1L,
        )
        val detail = state.toViewState(now).expandedEntry!!
        assertEquals(MemoryVisualState.EntryExpanded, state.toViewState(now).visualState)
        assertEquals("2 tok", detail.tokenLabel) // 8 chars / 4
        assertEquals("Chat \"Pixel 9 NPU setup\"", detail.learnedFromLabel)
        assertTrue(detail.usedInLabel!!.startsWith("6 replies"))
        assertEquals(listOf("knowledge"), detail.tags)
    }

    @Test
    fun `never-used chunk has null used-in`() {
        val state = MemoryUiState(memories = listOf(chunk(1, useCount = 0)), expandedId = 1L)
        assertNull(state.toViewState(now).expandedEntry!!.usedInLabel)
    }

    @Test
    fun `searching state carries relevance scores on rows`() {
        val state = MemoryUiState(
            memories = listOf(chunk(1)),
            searchActive = true,
            searchQuery = "x",
            searchResults = listOf(chunk(1) to 0.97f),
        )
        val vs = state.toViewState(now)
        assertEquals(MemoryVisualState.Searching, vs.visualState)
        assertTrue(vs.searchActive)
        assertEquals("0.97", vs.sections.flatMap { it.rows }.first().relevanceScore)
    }

    @Test
    fun `search results are one flat relevance-ordered section without time buckets`() {
        // An old high-relevance hit must stay ahead of a newer low-relevance hit.
        val state = MemoryUiState(
            memories = listOf(chunk(1, ageDays = 30), chunk(2, ageDays = 0)),
            searchActive = true,
            searchQuery = "x",
            searchResults = listOf(chunk(1, ageDays = 30) to 0.97f, chunk(2, ageDays = 0) to 0.40f),
        )
        val vs = state.toViewState(now)
        assertEquals(1, vs.sections.size)
        assertEquals("", vs.sections.single().title)
        assertEquals(listOf("1", "2"), vs.sections.single().rows.map { it.id })
    }

    @Test
    fun `searchEmpty is true when a search returns no hits`() {
        val state = MemoryUiState(
            memories = listOf(chunk(1)),
            searchActive = true,
            searchQuery = "zzz",
            searchResults = emptyList(),
        )
        val vs = state.toViewState(now)
        assertTrue(vs.searchEmpty)
        assertTrue(vs.sections.isEmpty())
    }

    @Test
    fun `loadFailed maps to the Error visual state`() {
        val state = MemoryUiState(memories = listOf(chunk(1)), loadFailed = true)
        assertEquals(MemoryVisualState.Error, state.toViewState(now).visualState)
    }

    // A pinned fact (1, manual, old) with an extracted update waiting (2, fresh), and
    // an unrelated fresh entry (3).
    private fun pairState(
        category: MemoryCategory = MemoryCategory.All,
        history: Map<Long, List<MemoryVersion>> = emptyMap(),
    ) = MemoryUiState(
        memories = listOf(
            chunk(1, ageDays = 30, pinned = true, text = "Lives in Berlin"),
            chunk(2, ageDays = 0, source = MemorySource.ChatSession("s1"), text = "Lives in Munich"),
            chunk(3, ageDays = 0, text = "Owns a cat"),
        ),
        pendingUpdates = listOf(MemoryPendingUpdate(updateChunkId = 2L, pinnedChunkId = 1L)),
        history = history,
        sessionNames = mapOf("s1" to "Flat viewings"),
        selectedCategory = category,
    )

    private fun version(id: Long, chunkId: Long, text: String) =
        MemoryVersion(id, chunkId, text, MemorySource.ChatSession("s1"), emptyList(), now - 2 * dayMs, now - dayMs)

    @Test
    fun `given a pair when listed then the update is drawn under its pinned entry and counted with it`() {
        // When
        val sections = pairState().toViewState(now).sections

        // Then — the update leaves Today and follows its pinned entry; the section counts entries, not rows.
        val pinned = sections.single { it.title == "Pinned" }
        assertEquals(listOf("1", "2"), pinned.rows.map { it.id })
        assertEquals(1, pinned.count)
        assertEquals(MemoryPairRole.PinnedWithUpdate, pinned.rows[0].pairRole)
        assertEquals(MemoryPairRole.UpdateOfPinned, pinned.rows[1].pairRole)
        assertTrue(pinned.rows[1].pairChild)
        assertEquals(listOf("3"), sections.single { it.title == "Today" }.rows.map { it.id })
    }

    @Test
    fun `given a source filter that shows only the update when listed then it stands alone with its status`() {
        // When — Auto shows the extracted update, not the manual pinned entry.
        val rows = pairState(MemoryCategory.Auto).toViewState(now).sections.flatMap { it.rows }

        // Then
        val update = rows.single()
        assertEquals("2", update.id)
        assertEquals(MemoryPairRole.UpdateOfPinned, update.pairRole)
        assertFalse(update.pairChild)
    }

    @Test
    fun `given the Pinned filter when listed then the update still follows its pinned entry`() {
        val sections = pairState(MemoryCategory.Pinned).toViewState(now).sections

        assertEquals(listOf("1", "2"), sections.single().rows.map { it.id })
        assertTrue(sections.single().rows[1].pairChild)
    }

    @Test
    fun `given a search that ranks the update first when listed then the pair stands at the pinned position`() {
        // Given
        val state = pairState().copy(
            searchActive = true,
            searchQuery = "where",
            searchResults = listOf(
                chunk(2, source = MemorySource.ChatSession("s1"), text = "Lives in Munich") to 0.9f,
                chunk(3, text = "Owns a cat") to 0.8f,
                chunk(1, ageDays = 30, pinned = true, text = "Lives in Berlin") to 0.7f,
            ),
        )

        // When
        val rows = state.toViewState(now).sections.single().rows

        // Then
        assertEquals(listOf("3", "1", "2"), rows.map { it.id })
        assertTrue(rows.last().pairChild)
        assertEquals(2, state.toViewState(now).sections.single().count)
    }

    @Test
    fun `given an entry with history when listed and opened then the row counts it and the sheet shows it`() {
        // Given
        val state = pairState(history = mapOf(3L to listOf(version(10, 3, "Owns a dog"), version(9, 3, "Owns a fish"))))
            .copy(expandedId = 3L)

        // When
        val view = state.toViewState(now)

        // Then
        val row = view.sections.flatMap { it.rows }.single { it.id == "3" }
        assertEquals(2, row.historyCount)
        val history = view.expandedEntry!!.history
        assertEquals(listOf("10", "9"), history.map { it.id })
        assertEquals("Owns a dog", history.first().text)
        assertEquals(MemorySourceKind.Auto, history.first().sourceKind)
        assertEquals("Flat viewings", history.first().learnedFrom)
        assertTrue(history.first().capturedLabel.isNotBlank())
        assertEquals(null, view.expandedEntry!!.pair)
    }

    @Test
    fun `given either half of a pair when opened then the sheet shows the other half`() {
        // When
        val fromPinned = pairState().copy(expandedId = 1L).toViewState(now).expandedEntry!!.pair!!
        val fromUpdate = pairState().copy(expandedId = 2L).toViewState(now).expandedEntry!!.pair!!

        // Then
        assertEquals(MemoryPairRole.PinnedWithUpdate, fromPinned.role)
        assertEquals("Lives in Munich", fromPinned.otherText)
        assertEquals("Flat viewings", fromPinned.otherLearnedFrom)
        assertEquals(MemoryPairRole.UpdateOfPinned, fromUpdate.role)
        assertEquals("Lives in Berlin", fromUpdate.otherText)
        assertEquals(MemorySourceKind.Manual, fromUpdate.otherSourceKind)
    }

    @Test
    fun `given a pair when chips are counted then Pinned counts pinned entries only`() {
        val chips = pairState().toViewState(now).categoryChips

        assertEquals(1, chips.single { it.category == MemoryCategory.Pinned }.count)
    }
}
