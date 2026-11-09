package com.gradientlabs.chatsdk.internal

import com.gradientlabs.chatsdk.GradientChat
import com.gradientlabs.chatsdk.GradientChatEvent
import com.gradientlabs.chatsdk.GradientChatListener
import com.gradientlabs.chatsdk.GradientChatUnavailableReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Exercises the widget-message → listener-callback mapping without a WebView by
 * driving [WidgetBridge.handleMessage] with the raw JSON the bridge receives.
 */
class WidgetBridgeTest {
    private data class CompletionSignal(
        val requestId: String,
        val accepted: Boolean,
        val errorCode: String?,
        val dispatchGeneration: Long,
    )

    private val events = mutableListOf<GradientChatEvent>()
    private val unavailable = mutableListOf<GradientChatUnavailableReason>()
    private val completionSignals = mutableListOf<CompletionSignal>()
    private val completionReceiver =
        { requestId: String, accepted: Boolean, errorCode: String?, dispatchGeneration: Long ->
            completionSignals +=
                CompletionSignal(requestId, accepted, errorCode, dispatchGeneration)
        }

    private val listener =
        object : GradientChatListener {
            override fun onChatEvent(event: GradientChatEvent) {
                events += event
            }

            override fun onChatUnavailable(reason: GradientChatUnavailableReason) {
                unavailable += reason
            }
        }

    @Before
    fun setUp() {
        GradientChatActivity.failInteractiveToolCompletions("test_cleanup")
        GradientChat.addListener(listener)
    }

    @After
    fun tearDown() {
        GradientChat.removeListener(listener)
        GradientChatActivity.failInteractiveToolCompletions("test_cleanup")
    }

    @Test
    fun unavailableMessageMapsToNotAvailable() {
        WidgetBridge.handleMessage("""{"type":"gradient-widget:unavailable"}""")
        assertEquals(listOf(GradientChatUnavailableReason.NOT_AVAILABLE), unavailable)
    }

    @Test
    fun mainFrameLoadFailureMapsToFailedToLoad() {
        // The activity's main-frame onReceivedError guard funnels into this seam.
        WidgetBridge.notifyUnavailable(GradientChatUnavailableReason.FAILED_TO_LOAD)
        assertEquals(listOf(GradientChatUnavailableReason.FAILED_TO_LOAD), unavailable)
    }

    @Test
    fun statusChangeObservingEmitsHandedOff() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:status-change","status":"observing"}""",
        )
        assertEquals(listOf<GradientChatEvent>(GradientChatEvent.HandedOff), events)
    }

    @Test
    fun statusChangeFinishedEmitsEnded() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:status-change","status":"finished"}""",
        )
        assertEquals(listOf<GradientChatEvent>(GradientChatEvent.Ended), events)
    }

    @Test
    fun statusChangeActiveEmitsNoEvent() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:status-change","status":"active"}""",
        )
        assertEquals(emptyList<GradientChatEvent>(), events)
    }

    @Test
    fun conversationReadyEmitsStartedThenResumedLifecycle() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:conversation-ready","external_id":"c1","internal_id":"i1","status":"observing"}""",
        )
        assertEquals(
            listOf(
                GradientChatEvent.Started(conversationId = "c1", internalId = "i1"),
                GradientChatEvent.HandedOff,
            ),
            events,
        )
    }

    @Test
    fun deeplinkMessageEmitsRequest() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:deeplink-requested","request_id":"async_tool_execution_1","url":"gradientsample://track_paycheck"}""",
        )

        assertEquals(
            listOf<GradientChatEvent>(
                GradientChatEvent.DeeplinkRequested(
                    requestId = "async_tool_execution_1",
                    url = "gradientsample://track_paycheck",
                ),
            ),
            events,
        )
    }

    @Test
    fun deeplinkMessageWithoutRequestIdEmitsLinkOnly() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:deeplink-requested","url":"gradientsample://track_paycheck"}""",
        )

        assertEquals(
            listOf<GradientChatEvent>(
                GradientChatEvent.DeeplinkRequested(
                    requestId = null,
                    url = "gradientsample://track_paycheck",
                ),
            ),
            events,
        )
    }

    @Test
    fun deeplinkMessageWithoutUrlEmitsNoEvent() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:deeplink-requested","request_id":"async_tool_execution_1","url":""}""",
        )
        assertEquals(emptyList<GradientChatEvent>(), events)
    }

    @Test
    fun interactiveToolCompletionMapsAcceptedAcknowledgement() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1","accepted":true,"attempt_id":"42"}""",
            completionReceiver,
        )

        assertEquals(
            listOf(
                CompletionSignal("async_tool_execution_1", true, null, 42),
            ),
            completionSignals,
        )
    }

    @Test
    fun interactiveToolCompletionMapsRejectedThenAcceptedAcknowledgements() {
        WidgetBridge.handleMessage(
            """
            {"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1",
             "accepted":false,"error_code":"network_error","attempt_id":"42"}
            """.trimIndent(),
            completionReceiver,
        )
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1","accepted":true,"attempt_id":"43"}""",
            completionReceiver,
        )

        assertEquals(
            listOf(
                CompletionSignal("async_tool_execution_1", false, "network_error", 42),
                CompletionSignal("async_tool_execution_1", true, null, 43),
            ),
            completionSignals,
        )
    }

    @Test
    fun malformedInteractiveToolCompletionIsIgnored() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1","attempt_id":"42"}""",
            completionReceiver,
        )

        assertEquals(emptyList<CompletionSignal>(), completionSignals)
    }

    @Test
    fun interactiveToolCompletionForwardsAttemptId() {
        WidgetBridge.handleMessage(
            """
            {"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1",
             "accepted":false,"error_code":"network_error","attempt_id":"42"}
            """.trimIndent(),
            completionReceiver,
        )

        assertEquals(
            listOf(CompletionSignal("async_tool_execution_1", false, "network_error", 42)),
            completionSignals,
        )
    }

    @Test
    fun malformedInteractiveToolAttemptIdIsIgnored() {
        var received = false

        WidgetBridge.handleMessage(
            """
            {"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1",
             "accepted":true,"attempt_id":"not-a-generation"}
            """.trimIndent(),
        ) { _, _, _, _ -> received = true }

        assertFalse(received)
    }

    @Test
    fun missingInteractiveToolAttemptIdIsIgnored() {
        WidgetBridge.handleMessage(
            """{"type":"gradient-widget:interactive-tool-completion","request_id":"async_tool_execution_1","accepted":true}""",
            completionReceiver,
        )

        assertEquals(emptyList<CompletionSignal>(), completionSignals)
    }

    @Test
    fun staleActivityBridgeMessageIsIgnored() {
        WidgetBridge.dispatchMessage(
            raw =
                """{"type":"gradient-widget:deeplink-requested","request_id":"async_tool_execution_1","url":"gradientsample://track_paycheck"}""",
            isOwningActivityCurrent = { false },
        )

        assertEquals(emptyList<GradientChatEvent>(), events)
    }
}
