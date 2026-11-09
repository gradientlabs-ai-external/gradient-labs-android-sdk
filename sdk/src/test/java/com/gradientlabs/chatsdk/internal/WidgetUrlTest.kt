package com.gradientlabs.chatsdk.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetUrlTest {
    @Test
    fun noTagsAddsNoTagParam() {
        assertEquals("#jwt=j&platform=android", WidgetUrl.fragment("j", null, emptyList(), shortSession = false))
    }

    @Test
    fun eachTagAddsOneRepeatedParamInOrder() {
        assertEquals(
            "#jwt=j&tag=vip&tag=billing&platform=android",
            WidgetUrl.fragment("j", null, listOf("vip", "billing"), shortSession = false),
        )
    }

    @Test
    fun tagIsPercentEncoded() {
        assertEquals(
            "#jwt=j&tag=a%20b%26c%3Dd%2Be%23f&platform=android",
            WidgetUrl.fragment("j", null, listOf("a b&c=d+e#f"), shortSession = false),
        )
    }

    @Test
    fun nonAsciiTagIsPercentEncodedAsUtf8() {
        assertEquals(
            "#jwt=j&tag=caf%C3%A9&tag=%E6%97%A5%E6%9C%AC&platform=android",
            WidgetUrl.fragment("j", null, listOf("café", "日本"), shortSession = false),
        )
    }

    @Test
    fun onlyAsciiLettersAndDigitsPassThroughUnencoded() {
        assertEquals(
            "#jwt=j&tag=Vip9%2D%5F%2E%7E&platform=android",
            WidgetUrl.fragment("j", null, listOf("Vip9-_.~"), shortSession = false),
        )
    }

    @Test
    fun tagsSitBetweenConversationTokenAndShortSessionFlag() {
        assertEquals(
            "#jwt=j&conversation_token=tok&tag=vip&tag=billing&use_short_session=1&platform=android",
            WidgetUrl.fragment("j", "tok", listOf("vip", "billing"), shortSession = true),
        )
    }

    @Test
    fun platformComesLast() {
        assertEquals(
            "#jwt=j&tag=vip&use_short_session=1&platform=android",
            WidgetUrl.fragment("j", null, listOf("vip"), shortSession = true),
        )
    }

    @Test
    fun emptyConversationTokenIsOmitted() {
        assertEquals("#jwt=j&tag=vip&platform=android", WidgetUrl.fragment("j", "", listOf("vip"), shortSession = false))
    }

    @Test
    fun conversationTokenIsPercentEncoded() {
        assertEquals(
            "#jwt=j&conversation_token=a%20b%26c&platform=android",
            WidgetUrl.fragment("j", "a b&c", emptyList(), shortSession = false),
        )
    }
}
