package com.gradientlabs.chatsdk.internal

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.gradientlabs.chatsdk.GradientChat
import com.gradientlabs.chatsdk.GradientChatAuthError
import com.gradientlabs.chatsdk.GradientChatEvent
import com.gradientlabs.chatsdk.GradientChatUnavailableReason
import org.json.JSONObject

internal object WidgetBridge {
    private const val TAG = "GradientChat"
    private const val JS_OBJECT_NAME = "GradientChatNativeBridge"
    private const val AUTH_ERROR_TYPE = "gradient-widget:auth-error"
    private const val CLOSE_TYPE = "gradient-widget:close"
    private const val CONVERSATION_READY_TYPE = "gradient-widget:conversation-ready"
    private const val STATUS_CHANGE_TYPE = "gradient-widget:status-change"
    private const val MESSAGE_RECEIVED_TYPE = "gradient-widget:message-received"
    private const val SURVEY_SUBMITTED_TYPE = "gradient-widget:survey-submitted"
    private const val UNAVAILABLE_TYPE = "gradient-widget:unavailable"
    private const val THEME_CHANGE_TYPE = "gradient-widget:theme-change"
    private const val DEEPLINK_REQUESTED_TYPE = "gradient-widget:deeplink-requested"
    private const val INTERACTIVE_TOOL_COMPLETION_TYPE =
        "gradient-widget:interactive-tool-completion"

    // RequiresFeature is a false positive: isFeatureSupported is checked below,
    // but lint doesn't recognise the early-return guard shape.
    @SuppressLint("RequiresFeature")
    fun install(
        webView: WebView,
        widgetOrigin: String,
        isOwningActivityCurrent: () -> Boolean,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(
                TAG,
                "WEB_MESSAGE_LISTENER unsupported; the auth-error bridge is " +
                    "disabled. The host should prompt the user to update " +
                    "Android System WebView.",
            )
            return
        }

        val originRoot =
            Uri.parse(widgetOrigin)
                .buildUpon()
                .path(null)
                .build()
                .toString()
                .trimEnd('/')

        WebViewCompat.addWebMessageListener(
            webView,
            JS_OBJECT_NAME,
            setOf(originRoot),
        ) { _, message, _, _, _ -> dispatch(message, isOwningActivityCurrent) }
    }

    private fun dispatch(
        message: WebMessageCompat,
        isOwningActivityCurrent: () -> Boolean,
    ) {
        dispatchMessage(
            raw = message.data ?: return,
            isOwningActivityCurrent = isOwningActivityCurrent,
        )
    }

    internal fun dispatchMessage(
        raw: String,
        isOwningActivityCurrent: () -> Boolean,
        completionReceiver: ((String, Boolean, String?, Long) -> Unit)? = null,
    ) {
        if (!isOwningActivityCurrent()) return
        handleMessage(raw, completionReceiver)
    }

    // Parse a widget bridge message and fan the corresponding SDK callback out
    // to listeners. Extracted from [dispatch] (which unwraps WebMessageCompat)
    // so the message → callback mapping is unit-testable without a WebView.
    internal fun handleMessage(
        raw: String,
        completionReceiver: ((String, Boolean, String?, Long) -> Unit)? = null,
    ) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (obj.optString("type")) {
            AUTH_ERROR_TYPE -> {
                val listeners = GradientChat.listeners
                if (listeners.isEmpty()) return
                val error = authErrorFor(obj.optString("code"))
                listeners.forEach { it.onAuthError(error) }
            }
            CLOSE_TYPE -> {
                GradientChatActivity.finishIfAlive()
            }
            CONVERSATION_READY_TYPE -> {
                GradientChatActivity.widgetReadyIfAlive()
                val listeners = GradientChat.listeners
                if (listeners.isEmpty()) return
                val started =
                    GradientChatEvent.Started(
                        conversationId = obj.optString("external_id"),
                        internalId = obj.optString("internal_id"),
                    )
                // A resumed conversation may already be handed off or ended;
                // surface that as a lifecycle event so it isn't lost.
                val resumed = lifecycleEventFor(obj.optString("status"))
                listeners.forEach {
                    it.onChatEvent(started)
                    if (resumed != null) it.onChatEvent(resumed)
                }
            }
            STATUS_CHANGE_TYPE -> {
                val lifecycle = lifecycleEventFor(obj.optString("status")) ?: return
                val listeners = GradientChat.listeners
                if (listeners.isEmpty()) return
                listeners.forEach { it.onChatEvent(lifecycle) }
            }
            MESSAGE_RECEIVED_TYPE -> {
                val listeners = GradientChat.listeners
                if (listeners.isEmpty()) return
                val event =
                    GradientChatEvent.MessageReceived(
                        messageId = obj.optString("message_id"),
                        sender = senderFor(obj.optString("sender")),
                    )
                listeners.forEach { it.onChatEvent(event) }
            }
            SURVEY_SUBMITTED_TYPE -> {
                val listeners = GradientChat.listeners
                if (listeners.isEmpty()) return
                val event =
                    GradientChatEvent.SurveySubmitted(
                        resolved = obj.optBoolean("resolved"),
                    )
                listeners.forEach { it.onChatEvent(event) }
            }
            UNAVAILABLE_TYPE -> {
                notifyUnavailable(GradientChatUnavailableReason.NOT_AVAILABLE)
            }
            THEME_CHANGE_TYPE -> {
                GradientChatActivity.widgetThemeChangedIfAlive(
                    isDark = obj.optString("theme") == "dark",
                )
            }
            DEEPLINK_REQUESTED_TYPE -> {
                val requestId = obj.optString("request_id").takeIf { it.isNotEmpty() }
                val url = obj.optString("url")
                if (url.isEmpty()) return
                val event = GradientChatEvent.DeeplinkRequested(requestId, url)
                GradientChat.listeners.forEach { it.onChatEvent(event) }
            }
            INTERACTIVE_TOOL_COMPLETION_TYPE -> {
                val requestId = obj.optString("request_id")
                if (requestId.isEmpty() || !obj.has("accepted")) return
                val attemptId = obj.optString("attempt_id").takeIf { it.isNotEmpty() } ?: return
                val dispatchGeneration = attemptId.toLongOrNull() ?: return
                val accepted = obj.optBoolean("accepted")
                val errorCode = obj.optString("error_code").takeIf { it.isNotEmpty() }
                if (completionReceiver == null) {
                    GradientChatActivity.interactiveToolCompletionReceived(
                        requestId = requestId,
                        accepted = accepted,
                        errorCode = errorCode,
                        dispatchGeneration = dispatchGeneration,
                    )
                } else {
                    completionReceiver(requestId, accepted, errorCode, dispatchGeneration)
                }
            }
        }
    }

    // Fan an unavailability out to listeners. Shared by the widget's own
    // "unavailable" signal and the activity's main-frame load failure.
    internal fun notifyUnavailable(reason: GradientChatUnavailableReason) {
        val listeners = GradientChat.listeners
        if (listeners.isEmpty()) return
        listeners.forEach { it.onChatUnavailable(reason) }
    }

    private fun authErrorFor(code: String): GradientChatAuthError =
        when (code) {
            "resource_exhausted" -> GradientChatAuthError.RateLimited
            "session_invalid" -> GradientChatAuthError.SessionInvalid
            else -> GradientChatAuthError.Unknown(code)
        }

    // Map the widget's backend status strings to the lifecycle event they
    // imply, or null when the status carries no lifecycle transition (e.g.
    // "active").
    internal fun lifecycleEventFor(status: String): GradientChatEvent? =
        when (status) {
            "observing" -> GradientChatEvent.HandedOff
            "finished", "discarded", "cancelled", "failed" -> GradientChatEvent.Ended
            else -> null
        }

    private fun senderFor(sender: String): GradientChatEvent.Sender =
        when (sender) {
            "customer" -> GradientChatEvent.Sender.CUSTOMER
            "agent" -> GradientChatEvent.Sender.AGENT
            else -> GradientChatEvent.Sender.AI
        }
}
