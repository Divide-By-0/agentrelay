package com.agentrelay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConversationHistoryManagerTest {

    @Before
    fun setup() {
        ConversationHistoryManager.clear()
    }

    // ── add and getAll ───────────────────────────────────────────────────────

    @Test
    fun `add stores items retrievable by getAll`() {
        val item = makeItem("test")

        ConversationHistoryManager.add(item)

        val all = ConversationHistoryManager.getAll()
        assertEquals(1, all.size)
        assertEquals("test", all[0].status)
    }

    @Test
    fun `multiple adds are ordered`() {
        ConversationHistoryManager.add(makeItem("first"))
        ConversationHistoryManager.add(makeItem("second"))
        ConversationHistoryManager.add(makeItem("third"))

        val all = ConversationHistoryManager.getAll()
        assertEquals(3, all.size)
        assertEquals("first", all[0].status)
        assertEquals("second", all[1].status)
        assertEquals("third", all[2].status)
    }

    // ── clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties history`() {
        ConversationHistoryManager.add(makeItem("item"))
        assertEquals(1, ConversationHistoryManager.getAll().size)

        ConversationHistoryManager.clear()

        assertEquals(0, ConversationHistoryManager.getAll().size)
    }

    // ── Capacity limit ───────────────────────────────────────────────────────

    @Test
    fun `capacity limit trims to 100 items`() {
        repeat(110) { i ->
            ConversationHistoryManager.add(makeItem("item_$i"))
        }

        val all = ConversationHistoryManager.getAll()
        assertEquals(100, all.size)
        // Oldest items should have been removed
        assertEquals("item_10", all[0].status)
        assertEquals("item_109", all[99].status)
    }

    // ── Listener notification ────────────────────────────────────────────────

    @Test
    fun `listener notified on add`() {
        var notifiedCount = 0
        var lastSnapshot: List<ConversationItem>? = null

        val listener: (List<ConversationItem>) -> Unit = { items ->
            notifiedCount++
            lastSnapshot = items
        }

        ConversationHistoryManager.addListener(listener)
        // addListener calls listener immediately with current state
        assertEquals(1, notifiedCount)

        ConversationHistoryManager.add(makeItem("new"))

        assertEquals(2, notifiedCount)
        assertEquals(1, lastSnapshot!!.size)

        ConversationHistoryManager.removeListener(listener)
    }

    @Test
    fun `listener notified on clear`() {
        ConversationHistoryManager.add(makeItem("item"))

        var notifiedAfterClear = false
        var snapshotAfterClear: List<ConversationItem>? = null

        val listener: (List<ConversationItem>) -> Unit = { items ->
            snapshotAfterClear = items
            notifiedAfterClear = true
        }

        ConversationHistoryManager.addListener(listener)
        // Reset: addListener triggers immediate call
        notifiedAfterClear = false

        ConversationHistoryManager.clear()

        assertTrue(notifiedAfterClear)
        assertEquals(0, snapshotAfterClear!!.size)

        ConversationHistoryManager.removeListener(listener)
    }

    @Test
    fun `removed listener is not notified`() {
        var callCount = 0
        val listener: (List<ConversationItem>) -> Unit = { callCount++ }

        ConversationHistoryManager.addListener(listener)
        assertEquals(1, callCount) // immediate call

        ConversationHistoryManager.removeListener(listener)
        ConversationHistoryManager.add(makeItem("ignored"))

        assertEquals(1, callCount) // no additional call
    }

    @Test
    fun `getAll returns defensive copy`() {
        ConversationHistoryManager.add(makeItem("a"))
        val snapshot1 = ConversationHistoryManager.getAll()

        ConversationHistoryManager.add(makeItem("b"))
        val snapshot2 = ConversationHistoryManager.getAll()

        assertEquals(1, snapshot1.size)
        assertEquals(2, snapshot2.size)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeItem(status: String) = ConversationItem(
        timestamp = System.currentTimeMillis(),
        type = ConversationItem.ItemType.API_RESPONSE,
        status = status
    )
}
