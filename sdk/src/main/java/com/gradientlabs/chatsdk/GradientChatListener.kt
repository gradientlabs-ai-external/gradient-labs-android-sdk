package com.gradientlabs.chatsdk

/** Why the widget's session could not be established or was lost. */
sealed interface GradientChatAuthError {
    /**
     * /widget-sessions is rate-limited. Back off before re-minting — re-minting
     * immediately just hits the limit again.
     */
    data object RateLimited : GradientChatAuthError

    /** The session is no longer valid; mint a fresh customer JWT. */
    data object SessionInvalid : GradientChatAuthError

    /** Any other auth failure. [code] is the raw widget error code, for logs. */
    data class Unknown(val code: String) : GradientChatAuthError
}

/** Why the chat can't be shown, delivered to [GradientChatListener.onChatUnavailable]. */
enum class GradientChatUnavailableReason {
    /** The chat can't function due to a problem on our side (not auth). */
    NOT_AVAILABLE,

    /** The chat screen failed to load the widget (main-frame load error, e.g. no network). */
    FAILED_TO_LOAD,
}

interface GradientChatListener {
    /**
     * The widget's session could not be established or is no longer valid. The
     * host should mint a fresh customer JWT and pass it to
     * [GradientChat.setUserJwt] (respecting backoff for [GradientChatAuthError.RateLimited]).
     */
    fun onAuthError(error: GradientChatAuthError) {}

    /** A chat lifecycle event occurred. Called on the main thread. */
    fun onChatEvent(event: GradientChatEvent) {}

    /**
     * The chat can't be shown — either a problem on our side or a failure to
     * load the widget (see [reason]). Not auth — that's [onAuthError]. The host
     * can hide the entry point or show a fallback. Called on the main thread.
     */
    fun onChatUnavailable(reason: GradientChatUnavailableReason) {}
}
