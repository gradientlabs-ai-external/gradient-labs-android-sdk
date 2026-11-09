package com.gradientlabs.chatsdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.gradientlabs.chatsdk.internal.GradientChatActivity
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object GradientChat {
    private const val TAG = "GradientChat"
    private const val PROD_WIDGET_ORIGIN = "https://chat-sdk-widget.vercel.app"

    @Volatile private var appContext: Context? = null

    @Volatile private var config: GradientChatConfig? = null

    @Volatile private var debugConfig: GradientChatDebugConfig? = null

    internal val listeners = CopyOnWriteArrayList<GradientChatListener>()

    /**
     * Initialise the SDK. Call once before presenting the chat (e.g. in your
     * Application). [debug] is for local development only — omit in production.
     */
    fun boot(
        context: Context,
        config: GradientChatConfig,
        debug: GradientChatDebugConfig? = null,
    ) {
        appContext = context.applicationContext
        this.config = config
        // Debug overrides are a trust boundary: an overridden widgetOrigin
        // receives the user JWT and the privileged WebView bridge. Only honour
        // them in debuggable builds.
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debug != null && !debuggable) {
            Log.w(TAG, "Ignoring GradientChatDebugConfig: the host app is not debuggable.")
            this.debugConfig = null
        } else {
            this.debugConfig = debug
        }
    }

    /**
     * Opens the chat UI. [boot] must have been called first. No-op (with a
     * warning) after [logout] until a fresh JWT is supplied via [setUserJwt]
     * or [boot].
     */
    fun present(activity: Activity) {
        val cfg = config ?: error("GradientChat.boot(...) must be called before present(...)")
        if (cfg.userJwt.isEmpty()) {
            Log.w(
                TAG,
                "present() ignored: logout() cleared the user JWT. Provide a " +
                    "fresh JWT via setUserJwt(...) or boot(...) first.",
            )
            return
        }
        activity.startActivity(Intent(activity, GradientChatActivity::class.java))
    }

    /**
     * Replace the user JWT (e.g. after re-minting on a [GradientChatAuthError],
     * or when a user logs back in after [logout]).
     *
     * [conversationToken] rides alongside the JWT but is never embedded in it.
     * null (the default) or "" leaves any stored token unchanged; a non-empty
     * value replaces it.
     */
    fun setUserJwt(
        jwt: String,
        conversationToken: String? = null,
    ) {
        val current = config ?: error("GradientChat.boot(...) must be called first")
        val resolvedToken = conversationToken?.takeIf { it.isNotEmpty() } ?: current.conversationToken
        config =
            current.copy(
                userJwt = jwt,
                conversationToken = resolvedToken,
            )
        GradientChatActivity.reloadIfAlive(jwt, resolvedToken)
    }

    /**
     * Register a listener for SDK callbacks (auth errors, chat events and
     * availability). Each registered listener receives every callback. Adding the
     * same instance twice is a no-op.
     *
     * Recommended scope: register once in `Application.onCreate` so the listener
     * lives for the process lifetime. Activity-scoped listeners must call
     * [removeListener] in `onDestroy` to avoid leaks.
     */
    fun addListener(listener: GradientChatListener) {
        listeners.addIfAbsent(listener)
    }

    /** Unregister a listener previously passed to [addListener]. No-op if not registered. */
    fun removeListener(listener: GradientChatListener) {
        listeners.remove(listener)
    }

    /**
     * Complete a native flow started by an interactive tool.
     *
     * [onCompletion] runs only after the widget reports that the backend accepted
     * the result, or when delivery fails. A failed result can safely be retried
     * with the same request ID. If the chat WebView is being recreated, the SDK
     * keeps the result pending and sends it once the widget is ready again.
     */
    fun completeInteractiveTool(
        requestId: String,
        result: Map<String, Any?>,
        onCompletion: (GradientChatInteractiveToolCompletion) -> Unit = {},
    ) {
        if (requestId.isBlank()) {
            onCompletion(GradientChatInteractiveToolCompletion.Failed("invalid_request_id"))
            return
        }
        val resultJson =
            runCatching { JSONObject(result).toString() }
                .getOrElse {
                    onCompletion(GradientChatInteractiveToolCompletion.Failed("invalid_result"))
                    return
                }
        GradientChatActivity.completeInteractiveTool(
            requestId,
            resultJson,
            onCompletion,
        )
    }

    /**
     * Call when the user logs out of the host app. Clears the user JWT (so
     * [present] is a no-op until a fresh one is supplied) and closes the chat
     * screen if it's open. Idempotent.
     */
    fun logout() {
        if (appContext == null) {
            // Before boot() there is no JWT or chat screen to clear, so there
            // is genuinely nothing to do.
            Log.w(TAG, "logout() called before boot(); nothing to clear.")
            return
        }
        config = config?.copy(userJwt = "")
        GradientChatActivity.failInteractiveToolCompletions("logged_out")
        GradientChatActivity.finishIfAlive()
    }

    /**
     * Closes the chat screen if it's open. Unlike [logout], the JWT stays so the
     * conversation resumes on the next [present].
     */
    fun dismiss() {
        GradientChatActivity.finishIfAlive()
    }

    internal fun widgetOrigin(): String = debugConfig?.widgetOrigin ?: PROD_WIDGET_ORIGIN

    internal fun shortSession(): Boolean = debugConfig?.shortSession ?: false

    /** The JWT the chat screen should load with, or null if not booted / logged out. */
    internal fun currentUserJwt(): String? = config?.userJwt?.takeIf { it.isNotEmpty() }

    /**
     * The conversation token to send with the current session, or null when
     * none is set. null or "" means "don't send the field".
     */
    internal fun currentConversationToken(): String? = config?.conversationToken

    internal fun currentTags(): List<String> = config?.tags.orEmpty()
}
