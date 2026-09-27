package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDraftTest {
    @Test
    fun keyTreatsNullAsNewSession() {
        assertEquals("", composerDraftKey(null))
        assertEquals("s1", composerDraftKey("s1"))
    }

    @Test
    fun stashKeepsDraftsOnTheirSessions() {
        val a = ComposerDraft("hello A", listOf("image/jpeg" to "aaa"))
        val (stored, loadedB) = stashComposerDraft(emptyMap(), "s1", "s2", a)
        assertEquals(a, stored["s1"])
        assertTrue(loadedB.isEmpty)
        val (back, loadedA) = stashComposerDraft(stored, "s2", "s1", ComposerDraft("hello B"))
        assertEquals("hello B", back["s2"]?.text)
        assertEquals(a, loadedA)
    }

    @Test
    fun stashSameKeyLeavesState() {
        val current = ComposerDraft("keep")
        val (stored, loaded) = stashComposerDraft(emptyMap(), "s1", "s1", current)
        assertTrue(stored.isEmpty())
        assertEquals(current, loaded)
    }

    @Test
    fun emptyDraftIsDroppedFromMap() {
        val start = mapOf("s1" to ComposerDraft("old"))
        val (stored, loaded) = stashComposerDraft(start, "s1", "s2", ComposerDraft())
        assertTrue("s1" !in stored)
        assertTrue(loaded.isEmpty)
    }

    @Test
    fun mergeTextAndCapImages() {
        assertEquals("hi", mergeComposerText(ComposerDraft(), "hi").text)
        assertEquals("hi there", mergeComposerText(ComposerDraft("hi"), "there").text)
        var draft = ComposerDraft()
        repeat(COMPOSER_MAX_IMAGES + 2) { i ->
            draft = appendComposerImage(draft, "image/jpeg" to "$i")
        }
        assertEquals(COMPOSER_MAX_IMAGES, draft.images.size)
    }

    @Test
    fun putRemovesEmpty() {
        val stored = putComposerDraft(mapOf("s1" to ComposerDraft("x")), "s1", ComposerDraft())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun switchParksAndRestoresComposerError() {
        val (parked, liveB) = switchComposerErrors(
            emptyMap(),
            fromKey = "s1",
            toKey = "s2",
            currentError = "read failed",
        )
        assertEquals("read failed", parked["s1"])
        assertEquals(null, liveB)
        val (restoredMap, liveA) = switchComposerErrors(parked, "s2", "s1", null)
        assertEquals("read failed", liveA)
        assertTrue("s1" !in restoredMap)
    }

    @Test
    fun switchSameKeyLeavesComposerError() {
        val (errors, live) = switchComposerErrors(
            mapOf("s2" to "other"),
            fromKey = "s1",
            toKey = "s1",
            currentError = "keep",
        )
        assertEquals("keep", live)
        assertEquals("other", errors["s2"])
    }

    @Test
    fun liveOrParkedComposerErrorFollowsOwner() {
        val (sameErrors, live) = liveOrParkedComposerError(emptyMap(), "s1", "s1", " boom ")
        assertEquals("boom", live)
        assertTrue(sameErrors.isEmpty())
        val (parked, noLive) = liveOrParkedComposerError(emptyMap(), "s1", "s2", "voice failed")
        assertEquals(null, noLive)
        assertEquals("voice failed", parked["s1"])
    }

    @Test
    fun storedDraftsTakeLiveSlotAndDropEmptyAndDeleted() {
        val drafts = mapOf(
            "s1" to ComposerDraft("stale s1"),
            "s2" to ComposerDraft("keep s2", listOf("image/jpeg" to "aaa")),
            "gone" to ComposerDraft("deleted session"),
            "img" to ComposerDraft("", listOf("image/jpeg" to "bbb")),
        )
        val out = storedDraftsFrom(drafts, "s1", ComposerDraft(""), emptyMap(), setOf("gone"), now = 1_000)
        assertEquals(setOf("s2"), out.keys)
        assertEquals(StoredDraft("keep s2", 1_000), out["s2"])
    }

    @Test
    fun storedDraftsKeepSavedAtWhenTextUnchanged() {
        val previous = mapOf("s1" to StoredDraft("same", 10), "s2" to StoredDraft("old", 10))
        val drafts = mapOf("s2" to ComposerDraft("new"))
        val out = storedDraftsFrom(drafts, "s1", ComposerDraft("same"), previous, emptySet(), now = 500)
        assertEquals(10L, out["s1"]?.savedAt)
        assertEquals(500L, out["s2"]?.savedAt)
    }

    @Test
    fun storedDraftsTruncateHugeText() {
        val huge = "x".repeat(STORED_DRAFT_MAX_CHARS + 50)
        val out = storedDraftsFrom(emptyMap(), "", ComposerDraft(huge), emptyMap(), emptySet(), now = 1)
        assertEquals(STORED_DRAFT_MAX_CHARS, out[""]?.text?.length)
    }

    @Test
    fun pruneDropsExpiredAndKeepsNewest() {
        val now = STORED_DRAFT_MAX_AGE_MS + 100
        val stored = mapOf(
            "old" to StoredDraft("a", 0),
            "n1" to StoredDraft("b", now - 1),
            "n2" to StoredDraft("c", now - 2),
            "n3" to StoredDraft("d", now - 3),
        )
        val out = pruneStoredDrafts(stored, now, maxEntries = 2)
        assertEquals(listOf("n1", "n2"), out.keys.toList())
    }

    @Test
    fun restoreKeepsInMemoryDraftsFirst() {
        val memory = mapOf("s1" to ComposerDraft("memory", listOf("image/png" to "p")))
        val stored = mapOf("s1" to StoredDraft("disk", 1), "s2" to StoredDraft("disk s2", 1))
        val out = restoreComposerDrafts(memory, stored)
        assertEquals(memory["s1"], out["s1"])
        assertEquals(ComposerDraft("disk s2"), out["s2"])
    }

    @Test
    fun storedDraftsRoundTripAndTolerateGarbage() {
        val stored = mapOf("" to StoredDraft("new session", 7), "s1" to StoredDraft("多行\n草稿", 8))
        assertEquals(stored, decodeStoredDrafts(encodeStoredDrafts(stored)))
        assertTrue(decodeStoredDrafts(null).isEmpty())
        assertTrue(decodeStoredDrafts("not json").isEmpty())
        assertTrue(decodeStoredDrafts("""{"s1":{"t":"  ","at":1}}""").isEmpty())
    }
}
