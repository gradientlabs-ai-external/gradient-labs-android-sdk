package com.gradientlabs.chatsdk.internal

import com.gradientlabs.chatsdk.GradientChatInteractiveToolCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveToolCompletionStoreTest {
    @Test
    fun completionStaysPendingUntilResolved() {
        val outcomes = mutableListOf<GradientChatInteractiveToolCompletion>()
        val store = InteractiveToolCompletionStore()
        val completion =
            PendingInteractiveToolCompletion(
                requestId = "request_1",
                resultJson = """{"status":"completed"}""",
                callback = outcomes::add,
            )

        assertTrue(store.add(completion))
        assertEquals(listOf(completion), store.snapshot())
        assertEquals(emptyList<GradientChatInteractiveToolCompletion>(), outcomes)

        store.advanceGeneration(1)
        assertTrue(store.beginDispatch("request_1", generation = 1))
        assertTrue(
            store.resolveDispatch(
                "request_1",
                generation = 1,
                GradientChatInteractiveToolCompletion.Accepted,
            ),
        )
        assertEquals(emptyList<PendingInteractiveToolCompletion>(), store.snapshot())
        assertEquals(
            listOf<GradientChatInteractiveToolCompletion>(
                GradientChatInteractiveToolCompletion.Accepted,
            ),
            outcomes,
        )
    }

    @Test
    fun duplicateIsRejectedWhileOriginalIsPending() {
        val store = InteractiveToolCompletionStore()
        val completion =
            PendingInteractiveToolCompletion("request_1", "{}") {}

        assertTrue(store.add(completion))
        assertFalse(store.add(completion))
    }

    @Test
    fun staleAttemptAcknowledgementCannotResolveNewAttempt() {
        val outcomes = mutableListOf<GradientChatInteractiveToolCompletion>()
        val store = InteractiveToolCompletionStore()
        assertTrue(
            store.add(
                PendingInteractiveToolCompletion("request_1", "{}", outcomes::add),
            ),
        )

        assertTrue(store.beginDispatch("request_1", generation = 1))
        assertFalse(store.beginDispatch("request_1", generation = 1))
        assertTrue(store.beginDispatch("request_1", generation = 2))
        assertFalse(store.beginDispatch("request_1", generation = 1))

        assertFalse(
            store.resolveDispatch(
                "request_1",
                generation = 1,
                GradientChatInteractiveToolCompletion.Accepted,
            ),
        )
        assertEquals(emptyList<GradientChatInteractiveToolCompletion>(), outcomes)
        assertEquals(1, store.snapshot().size)

        assertTrue(
            store.resolveDispatch(
                "request_1",
                generation = 2,
                GradientChatInteractiveToolCompletion.Accepted,
            ),
        )
        assertEquals(
            listOf(GradientChatInteractiveToolCompletion.Accepted),
            outcomes,
        )
        assertEquals(emptyList<PendingInteractiveToolCompletion>(), store.snapshot())
    }

    @Test
    fun sameActivityDocumentReloadCanReclaimPendingCompletion() {
        val outcomes = mutableListOf<GradientChatInteractiveToolCompletion>()
        val store = InteractiveToolCompletionStore()
        assertTrue(
            store.add(
                PendingInteractiveToolCompletion("request_1", "{}", outcomes::add),
            ),
        )
        assertTrue(store.beginDispatch("request_1", generation = 10))

        assertTrue(store.beginDispatch("request_1", generation = 11))
        assertFalse(
            store.resolveDispatch(
                "request_1",
                generation = 10,
                GradientChatInteractiveToolCompletion.Failed("stale_document"),
            ),
        )
        assertTrue(
            store.resolveDispatch(
                "request_1",
                generation = 11,
                GradientChatInteractiveToolCompletion.Accepted,
            ),
        )

        assertEquals(
            listOf(GradientChatInteractiveToolCompletion.Accepted),
            outcomes,
        )
    }

    @Test
    fun advancingDocumentRetiresFailureBeforeNewDocumentDispatches() {
        val outcomes = mutableListOf<GradientChatInteractiveToolCompletion>()
        val store = InteractiveToolCompletionStore()
        assertTrue(
            store.add(
                PendingInteractiveToolCompletion("request_1", "{}", outcomes::add),
            ),
        )
        store.advanceGeneration(10)
        assertTrue(store.beginDispatch("request_1", generation = 10))

        store.advanceGeneration(11)
        assertFalse(store.failDispatch("request_1", generation = 10, "stale_document"))
        assertEquals(emptyList<GradientChatInteractiveToolCompletion>(), outcomes)
        assertEquals(1, store.snapshot().size)

        assertTrue(store.beginDispatch("request_1", generation = 11))
        assertTrue(
            store.resolveDispatch(
                "request_1",
                generation = 11,
                GradientChatInteractiveToolCompletion.Accepted,
            ),
        )
        assertEquals(
            listOf(GradientChatInteractiveToolCompletion.Accepted),
            outcomes,
        )
    }
}
