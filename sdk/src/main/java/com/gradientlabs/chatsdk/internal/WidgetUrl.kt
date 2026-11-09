package com.gradientlabs.chatsdk.internal

internal object WidgetUrl {
    // The conversation token rides in the fragment alongside the JWT (never
    // inside it); the widget forwards it as the conversation_token body field
    // when exchanging the session. A null/empty token omits the param so the
    // stored token is left unchanged. Each tag adds one "&tag=" param.
    // "&platform=android" tells the widget which app it is running in.
    fun fragment(
        jwt: String,
        conversationToken: String?,
        tags: List<String>,
        shortSession: Boolean,
    ): String =
        buildString {
            append("#jwt=").append(jwt)
            if (!conversationToken.isNullOrEmpty()) {
                append("&conversation_token=").append(percentEncoded(conversationToken))
            }
            tags.forEach { append("&tag=").append(percentEncoded(it)) }
            if (shortSession) append("&use_short_session=1")
            append("&platform=android")
        }

    // Matches iOS's addingPercentEncoding(withAllowedCharacters: .alphanumerics):
    // only ASCII letters and digits pass through, every other UTF-8 byte is escaped.
    private fun percentEncoded(value: String): String =
        buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val b = byte.toInt() and 0xFF
                val c = b.toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
                    append(c)
                } else {
                    append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
                }
            }
        }

    private const val HEX = "0123456789ABCDEF"
}
