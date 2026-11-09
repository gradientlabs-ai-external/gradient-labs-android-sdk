package com.gradientlabs.chatsdk.internal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gradientlabs.chatsdk.GradientChat
import com.gradientlabs.chatsdk.GradientChatInteractiveToolCompletion
import com.gradientlabs.chatsdk.GradientChatUnavailableReason
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

internal class GradientChatActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private lateinit var widgetOrigin: String
    private var debugShortSession: Boolean = false
    private var lastLoadedJwt: String? = null
    private var lastLoadedConversationToken: String? = null
    private var lastLoadedTags: List<String> = emptyList()
    private var widgetReady = false
    private var interactiveToolDispatchGeneration = 0L
    private var expectingMainFrameLoadStart = false

    // Holds the WebView's file-chooser callback while the system picker is open.
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // FileChooserParams.parseResult returns null for a multi-selection on
            // some Android versions even when the ClipData holds the URIs, so read
            // them directly: ClipData for multiple, getData() for a single file.
            val data = result.data
            val clip = data?.clipData
            val single = data?.data
            val uris: Array<Uri>? =
                when {
                    result.resultCode != RESULT_OK -> null
                    clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                    single != null -> arrayOf(single)
                    else -> null
                }
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Config and JWT come from the GradientChat singleton, never from the
        // intent: after process death the system recreates this activity from
        // a saved intent, which would otherwise carry a stale JWT while the
        // singleton is gone. If the SDK isn't booted (or logout() cleared the
        // JWT), bail out — the host reopens chat via its normal flow, which
        // re-boots with a fresh JWT.
        val userJwt = GradientChat.currentUserJwt()
        if (userJwt == null) {
            Log.w(TAG, "Chat opened without a booted SDK (process death or logout); closing.")
            finish()
            return
        }
        widgetOrigin = GradientChat.widgetOrigin()
        debugShortSession = GradientChat.shortSession()

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        webView =
            WebView(this).apply {
                // Transparent WebView: the default opaque white canvas flashes
                // before the widget paints and shows through overscroll —
                // glaring when the widget is dark. The container behind it
                // tracks the widget's resolved theme (see applyChromeTheme).
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Widget only loads its remote https origin; deny the page direct
                // file:// / content:// access. Attachment uploads go through the
                // system picker in onShowFileChooser below, which doesn't need these.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                WebView.setWebContentsDebuggingEnabled(isDebuggable)
                webViewClient = OffOriginInterceptingClient(widgetOrigin)
                webChromeClient = fileChooserChromeClient()
            }

        val owningActivity = WeakReference(this)
        WidgetBridge.install(webView, widgetOrigin) {
            val owner = owningActivity.get()
            owner != null && instance?.get() === owner
        }

        // WebView ignores its own setPadding for HTML rendering, so we put it
        // inside a FrameLayout and pad the container instead. Padding on the
        // container shrinks the WebView's bounds, which the rendered HTML
        // respects.
        container =
            FrameLayout(this).apply {
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        setContentView(container)

        // First-paint chrome from the OS preference; the widget echoes its
        // resolved theme through the bridge (theme-change) once loaded, which
        // corrects for the company setting.
        applyChromeTheme(isDark = isSystemDark())
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
                        or WindowInsetsCompat.Type.ime(),
                )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        // Route the system back button into the widget first: when the WebView has
        // in-page history (e.g. the full-screen image viewer pushed a state), go
        // back there so back dismisses the overlay instead of closing the whole
        // chat. With no in-page history, fall through to the default (finish).
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        instance = WeakReference(this)
        loadWithJwt(userJwt)
    }

    // launchMode is singleTop, so present() while the chat is already on top
    // lands here instead of stacking a second WebView. Reload only if the JWT,
    // conversation token or tags changed under us, otherwise keep the live
    // conversation as-is.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val jwt = GradientChat.currentUserJwt()
        if (jwt == null) {
            finish()
            return
        }
        reload(jwt, GradientChat.currentConversationToken())
    }

    // Full page load — used for the initial onCreate render and after a load
    // failure. reload() handles in-place updates once the page is live.
    private fun loadWithJwt(jwt: String) {
        val token = GradientChat.currentConversationToken()
        val tags = GradientChat.currentTags()
        lastLoadedJwt = jwt
        lastLoadedConversationToken = token
        lastLoadedTags = tags
        beginWidgetDocumentLoad("$widgetOrigin/${buildFragment(jwt, token, tags)}")
    }

    private fun beginWidgetDocumentLoad(url: String) {
        beginNewWidgetDocument()
        expectingMainFrameLoadStart = true
        webView.loadUrl(url)
    }

    private fun mainFrameLoadStarted() {
        if (expectingMainFrameLoadStart) {
            expectingMainFrameLoadStart = false
            return
        }
        beginNewWidgetDocument()
    }

    private fun beginNewWidgetDocument() {
        // A destroyed Activity can still receive a late onPageStarted callback;
        // it must not retire the replacement Activity's newer document.
        if (instance?.get() !== this) return
        widgetReady = false
        interactiveToolDispatchGeneration =
            nextInteractiveToolDispatchGeneration.getAndIncrement()
        interactiveToolCompletions.advanceGeneration(interactiveToolDispatchGeneration)
    }

    // Apply a new JWT, conversation token or tags to the already-loaded page.
    // A nil token leaves the last-loaded token unchanged; reload is skipped
    // entirely when nothing changed.
    private fun reload(
        jwt: String,
        conversationToken: String?,
    ) {
        val nextToken = conversationToken ?: lastLoadedConversationToken
        val tags = GradientChat.currentTags()
        if (jwt == lastLoadedJwt && nextToken == lastLoadedConversationToken && tags == lastLoadedTags) return
        lastLoadedJwt = jwt
        lastLoadedConversationToken = nextToken
        lastLoadedTags = tags
        // A fragment assignment fires the widget's hashchange JWT path without
        // reloading the page, so the live conversation DOM is preserved.
        webView.evaluateJavascript("location.hash = ${jsString(buildFragment(jwt, nextToken, tags))}", null)
    }

    private fun widgetReady() {
        widgetReady = true
        dispatchPendingInteractiveToolCompletions()
    }

    private fun dispatchPendingInteractiveToolCompletions() {
        if (!widgetReady) return
        interactiveToolCompletions.snapshot().forEach(::dispatchInteractiveToolCompletion)
    }

    private fun dispatchInteractiveToolCompletion(completion: PendingInteractiveToolCompletion) {
        // The callback may arrive after this WebView has loaded another
        // document, so it must resolve against the generation it dispatched to.
        val dispatchGeneration = interactiveToolDispatchGeneration
        if (
            !interactiveToolCompletions.beginDispatch(
                completion.requestId,
                dispatchGeneration,
            )
        ) {
            return
        }
        val attemptId = dispatchGeneration.toString()
        val script =
            "window.GradientWidget?.completeInteractiveTool(" +
                "${JSONObject.quote(completion.requestId)}, ${completion.resultJson}, " +
                "${JSONObject.quote(attemptId)})"
        try {
            webView.evaluateJavascript(script) { dispatchResult ->
                if (
                    dispatchResult != "true" &&
                    isCurrentWidgetGeneration(dispatchGeneration)
                ) {
                    interactiveToolCompletions.failDispatch(
                        completion.requestId,
                        dispatchGeneration,
                        "widget_dispatch_failed",
                    )
                }
            }
        } catch (_: Throwable) {
            if (isCurrentWidgetGeneration(dispatchGeneration)) {
                interactiveToolCompletions.failDispatch(
                    completion.requestId,
                    dispatchGeneration,
                    "widget_dispatch_failed",
                )
            }
        }
    }

    private fun buildFragment(
        jwt: String,
        conversationToken: String?,
        tags: List<String>,
    ): String {
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return WidgetUrl.fragment(jwt, conversationToken, tags, shortSession = debugShortSession && isDebuggable)
    }

    // The chrome around the transparent WebView: the container paints the
    // system-bar strips, and the bar icons need the opposite polarity of the
    // surface behind them. Colors mirror the widget's surface tokens.
    private fun applyChromeTheme(isDark: Boolean) {
        container.setBackgroundColor(if (isDark) DARK_SURFACE else Color.WHITE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // Single-quoted JS string literal, escaped so a hostile value can't break
    // out of the quotes when passed to evaluateJavascript.
    private fun jsString(value: String): String {
        val escaped =
            value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        return "'$escaped'"
    }

    override fun onDestroy() {
        // Resolve any open picker callback so the WebView isn't left waiting.
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (instance?.get() === this) instance = null
        // onCreate may have finished early before creating the WebView.
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun fileChooserChromeClient() =
        object : WebChromeClient() {
            // A WebView opens no file picker on its own; this bridges the widget's
            // <input type="file"> to the system picker and returns the selection.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                val intent = params?.createIntent() ?: return false
                // createIntent() doesn't carry the multi-select flag, so an
                // <input multiple> would only ever return one file without this.
                if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                // Cancel any in-flight request so its callback isn't left hanging.
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    true
                }
            }
        }

    private inner class OffOriginInterceptingClient(origin: String) : WebViewClient() {
        private val originUri = Uri.parse(origin)

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            mainFrameLoadStarted()
            super.onPageStarted(view, url, favicon)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            // On-origin navigations stay in the widget. Compare parsed
            // scheme+host+port, not a string prefix: a navigation to the bare
            // origin (no trailing slash) must not be treated as off-origin.
            val url = request.url
            if (isSameOrigin(url)) return false
            // Everything else goes to the system (browser, dialer, mail, …). We
            // always claim to have handled it: never load off-origin content in
            // this WebView, even if no app can open the URL. Strip the fragment
            // first — widget URLs carry the user JWT in the fragment and it
            // must never leak to an external app.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.buildUpon().fragment(null).build()))
            } catch (_: Throwable) {
                // No app to handle it; drop the navigation rather than loading
                // it here.
            }
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Sub-resource failures are the widget's problem; a main-frame
            // failure means the chat screen is blank, so tell the host.
            if (!request.isForMainFrame) return
            Log.w(TAG, "Widget load failed: ${error.errorCode} ${error.description}")
            WidgetBridge.notifyUnavailable(GradientChatUnavailableReason.FAILED_TO_LOAD)
        }

        private fun isSameOrigin(uri: Uri): Boolean =
            uri.scheme.equals(originUri.scheme, ignoreCase = true) &&
                uri.host.equals(originUri.host, ignoreCase = true) &&
                portOf(uri) == portOf(originUri)

        private fun portOf(uri: Uri): Int =
            when {
                uri.port != -1 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                uri.scheme.equals("http", ignoreCase = true) -> 80
                else -> -1
            }
    }

    companion object {
        private const val TAG = "GradientChat"

        // The widget's dark --surface token (#020617).
        private const val DARK_SURFACE = 0xFF020617.toInt()

        @Volatile private var instance: WeakReference<GradientChatActivity>? = null

        private val interactiveToolCompletions = InteractiveToolCompletionStore()
        private val nextInteractiveToolDispatchGeneration = AtomicLong(1)

        internal fun reloadIfAlive(
            jwt: String,
            conversationToken: String?,
        ) {
            val activity = instance?.get() ?: return
            activity.runOnUiThread { activity.reload(jwt, conversationToken) }
        }

        /** The widget resolved its effective theme; restyle the native chrome. */
        internal fun widgetThemeChangedIfAlive(isDark: Boolean) {
            val activity = instance?.get() ?: return
            activity.runOnUiThread { activity.applyChromeTheme(isDark) }
        }

        internal fun completeInteractiveTool(
            requestId: String,
            resultJson: String,
            callback: (GradientChatInteractiveToolCompletion) -> Unit,
        ) {
            val added =
                interactiveToolCompletions.add(
                    PendingInteractiveToolCompletion(requestId, resultJson, callback),
                )
            if (!added) {
                callback(GradientChatInteractiveToolCompletion.Failed("already_pending"))
                return
            }
            val activity = instance?.get() ?: return
            activity.runOnUiThread { activity.dispatchPendingInteractiveToolCompletions() }
        }

        internal fun widgetReadyIfAlive() {
            val activity = instance?.get() ?: return
            activity.runOnUiThread { activity.widgetReady() }
        }

        internal fun isCurrentWidgetGeneration(generation: Long): Boolean = instance?.get()?.interactiveToolDispatchGeneration == generation

        internal fun interactiveToolCompletionReceived(
            requestId: String,
            accepted: Boolean,
            errorCode: String?,
            dispatchGeneration: Long,
        ) {
            val outcome =
                if (accepted) {
                    GradientChatInteractiveToolCompletion.Accepted
                } else {
                    GradientChatInteractiveToolCompletion.Failed(errorCode ?: "unknown")
                }
            interactiveToolCompletions.resolveDispatch(
                requestId,
                dispatchGeneration,
                outcome,
            )
        }

        internal fun failInteractiveToolCompletions(errorCode: String) {
            interactiveToolCompletions.failAll(errorCode)
        }

        /** Closes the chat screen if it's open (e.g. on logout). No-op otherwise. */
        internal fun finishIfAlive() {
            val activity = instance?.get() ?: return
            activity.runOnUiThread { activity.finish() }
        }
    }
}
