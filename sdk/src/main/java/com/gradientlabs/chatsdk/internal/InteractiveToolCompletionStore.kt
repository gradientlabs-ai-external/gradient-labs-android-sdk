package com.gradientlabs.chatsdk.internal

import com.gradientlabs.chatsdk.GradientChatInteractiveToolCompletion

internal data class PendingInteractiveToolCompletion(
    val requestId: String,
    val resultJson: String,
    val callback: (GradientChatInteractiveToolCompletion) -> Unit,
)

/** Keeps native results pending until the widget acknowledges the backend outcome. */
internal class InteractiveToolCompletionStore {
    private val pending = linkedMapOf<String, PendingInteractiveToolCompletion>()
    private val dispatchGenerations = mutableMapOf<String, Long>()
    private var latestGeneration = 0L

    @Synchronized
    fun add(completion: PendingInteractiveToolCompletion): Boolean {
        if (pending.containsKey(completion.requestId)) return false
        pending[completion.requestId] = completion
        return true
    }

    @Synchronized
    fun snapshot(): List<PendingInteractiveToolCompletion> = pending.values.toList()

    /** Retires callbacks from every document older than [generation]. */
    @Synchronized
    fun advanceGeneration(generation: Long) {
        if (generation > latestGeneration) latestGeneration = generation
    }

    /**
     * Claims delivery for a WebView generation. A newer WebView supersedes an
     * older in-flight dispatch, while repeated ready events from the same
     * WebView do not submit the result twice.
     */
    @Synchronized
    fun beginDispatch(
        requestId: String,
        generation: Long,
    ): Boolean {
        if (!pending.containsKey(requestId)) return false
        if (generation < latestGeneration) return false
        if (generation > latestGeneration) latestGeneration = generation
        val currentGeneration = dispatchGenerations[requestId]
        if (currentGeneration != null && generation <= currentGeneration) return false
        dispatchGenerations[requestId] = generation
        return true
    }

    fun failDispatch(
        requestId: String,
        generation: Long,
        errorCode: String,
    ): Boolean =
        resolveDispatch(
            requestId,
            generation,
            GradientChatInteractiveToolCompletion.Failed(errorCode),
        )

    fun resolveDispatch(
        requestId: String,
        generation: Long,
        outcome: GradientChatInteractiveToolCompletion,
    ): Boolean {
        val completion =
            synchronized(this) {
                if (generation != latestGeneration) return false
                if (dispatchGenerations[requestId] != generation) return false
                dispatchGenerations.remove(requestId)
                pending.remove(requestId)
            } ?: return false
        completion.callback(outcome)
        return true
    }

    fun failAll(errorCode: String) {
        val completions =
            synchronized(this) {
                val values = pending.values.toList()
                pending.clear()
                dispatchGenerations.clear()
                values
            }
        val outcome = GradientChatInteractiveToolCompletion.Failed(errorCode)
        completions.forEach { it.callback(outcome) }
    }
}
