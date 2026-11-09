package com.gradientlabs.chatsdk

/**
 * Identifies the company (via [widgetKey]) and the end user (via [userJwt],
 * a short-lived identity-only HS256 JWT minted by the customer's backend).
 *
 * [conversationToken] is an optional conversation-scoped token, sent alongside
 * the JWT but never embedded in it. A non-empty value is forwarded on
 * conversation tool and webhook calls. null or "" omits it and leaves any
 * previously stored token unchanged on a refresh.
 *
 * [tags] label a new conversation and are ignored when an existing one resumes.
 */
data class GradientChatConfig(
    val widgetKey: String,
    val userJwt: String,
    val conversationToken: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * Debug-only overrides for local development. Production integrations never
 * construct this — the SDK targets Gradient's production widget + API by
 * default. Used by the sample harness to point at a local widget / backend
 * and exercise short-lived sessions.
 */
data class GradientChatDebugConfig(
    /** Override the widget origin (e.g. "http://localhost:5173"). null → production. */
    val widgetOrigin: String? = null,
    /** Ask the backend to mint 15s sessions to test the expiry path. */
    val shortSession: Boolean = false,
)
