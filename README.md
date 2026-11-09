# Gradient Chat SDK (Android)

Embed Gradient Labs' AI chat in your Android app. A thin wrapper that hosts the
chat in a WebView and exposes a small native API (`boot`, `present`, listeners).
Min SDK 24.

## See it working

Install the sample app from [Firebase App Distribution](https://appdistribution.firebase.dev/i/06143a9e7fa88dd3),
no build needed.

## Add it to your app

The SDK is served by [JitPack](https://jitpack.io/#gradientlabs-ai-external/gradient-labs-android-sdk),
straight from this repo's release tags. No token is needed.

Add the repository to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.gradientlabs-ai-external") }
        }
    }
}
```

Then add the dependency, using a tag from
[Releases](https://github.com/gradientlabs-ai-external/gradient-labs-android-sdk/releases):

```kotlin
implementation("com.github.gradientlabs-ai-external:gradient-labs-android-sdk:v0.7.1")
```

## Quick start

Your backend mints a short-lived HS256 JWT (5 minutes recommended), signed with
your widget secret. Its protected header contains `alg: HS256` and the widget
key as `kid`; its claims contain the user's ID as `sub` plus `iat` and `exp`.
The lifetime (`exp - iat`) must not exceed 1 hour. Find the widget key and
secret in `app.gradient-labs.ai/<company>/<branch>/workspace-settings/chat-sdk`.

```kotlin
GradientChat.boot(this, GradientChatConfig(widgetKey = "wpk_…", userJwt = backend.fetchJwt()))
GradientChat.present(this)
```

Call `GradientChat.logout()` on sign-out.

## Listening for callbacks

Register a `GradientChatListener` (all three callbacks are optional). The
listener surface is intentionally small — three callbacks:

```kotlin
GradientChat.addListener(object : GradientChatListener {
    // The session couldn't be established / expired — mint a fresh JWT.
    // Respect backoff for GradientChatAuthError.RateLimited.
    override fun onAuthError(error: GradientChatAuthError) {
        GradientChat.setUserJwt(backend.fetchJwt())
    }

    // Chat lifecycle events: Started, HandedOff, MessageReceived, Ended, SurveySubmitted.
    override fun onChatEvent(event: GradientChatEvent) {
        when (event) {
            is GradientChatEvent.Started -> { /* event.conversationId, event.internalId */ }
            GradientChatEvent.HandedOff -> { /* a human agent took over */ }
            is GradientChatEvent.MessageReceived -> { /* event.messageId, event.sender */ }
            GradientChatEvent.Ended -> { /* the conversation ended */ }
            is GradientChatEvent.SurveySubmitted -> { /* event.resolved */ }
            is GradientChatEvent.DeeplinkRequested -> {
                // Open the screen event.url points to, over the chat or after
                // GradientChat.dismiss(). When event.requestId is set, report
                // the flow's result using
                // GradientChat.completeInteractiveTool(event.requestId, result).
            }
        }
    }

    // The chat can't be shown — hide the entry point or show a fallback.
    // reason is NOT_AVAILABLE (a problem on our side) or FAILED_TO_LOAD (the
    // widget failed to load, e.g. no network).
    override fun onChatUnavailable(reason: GradientChatUnavailableReason) { /* … */ }
})
```

Register once in `Application.onCreate` (lives for the process) or at Activity
scope with a paired `removeListener` in `onDestroy`. All callbacks fire on the
main thread.
