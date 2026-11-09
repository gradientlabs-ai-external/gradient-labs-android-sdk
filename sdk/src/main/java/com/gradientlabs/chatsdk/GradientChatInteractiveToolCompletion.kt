package com.gradientlabs.chatsdk

/** The acknowledged outcome of [GradientChat.completeInteractiveTool]. */
sealed interface GradientChatInteractiveToolCompletion {
    /** The widget delivered the result and the backend accepted it. */
    data object Accepted : GradientChatInteractiveToolCompletion

    /** The result could not be delivered or was rejected. The same request can be retried. */
    data class Failed(
        val errorCode: String,
    ) : GradientChatInteractiveToolCompletion
}
