package com.gradientlabs.chatsdk

/** A chat lifecycle event, delivered to [GradientChatListener.onChatEvent]. */
sealed interface GradientChatEvent {
    /** A conversation became active. */
    data class Started(
        val conversationId: String,
        val internalId: String,
    ) : GradientChatEvent

    /** The conversation was handed off to a human agent. */
    data object HandedOff : GradientChatEvent

    /** A new message arrived — id and sender only, no body. */
    data class MessageReceived(
        val messageId: String,
        val sender: Sender,
    ) : GradientChatEvent

    /** The conversation ended. */
    data object Ended : GradientChatEvent

    /**
     * The customer answered the end-of-conversation CSAT survey. Carries the
     * answer to "Was your issue resolved?" only — the free-text follow-up
     * never crosses the bridge.
     */
    data class SurveySubmitted(
        val resolved: Boolean,
    ) : GradientChatEvent

    /**
     * The customer tapped a link into the host app. With a [requestId] an
     * interactive tool is waiting: open the native flow, then call
     * [GradientChat.completeInteractiveTool]. Without one (a link in an agent
     * reply), just open the screen. Either way the host chooses to open it over
     * the chat or call [GradientChat.dismiss] first.
     */
    data class DeeplinkRequested(
        val requestId: String?,
        val url: String,
    ) : GradientChatEvent

    /** Who sent a [MessageReceived]. */
    enum class Sender { AI, AGENT, CUSTOMER }
}
