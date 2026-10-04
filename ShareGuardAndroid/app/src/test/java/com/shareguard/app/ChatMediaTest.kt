package com.shareguard.app

import com.shareguard.app.data.ChatMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMediaTest {
    @Test fun mediaMarkerRoundTripsWithoutTreatingOrdinaryMessagesAsFiles() {
        val media = ChatMedia("image", "room-id/user-id/photo.jpg")
        assertEquals(media, ChatMedia.fromBody(media.asBody()))
        assertNull(ChatMedia.fromBody("안녕하세요"))
        assertNull(ChatMedia.fromBody("[[shareguard-media:image:../../private.jpg]]"))
    }
}
