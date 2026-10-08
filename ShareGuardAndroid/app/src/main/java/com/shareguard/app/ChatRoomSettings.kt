package com.shareguard.app

import android.content.Context

/** Per-account chat presentation flags used by the existing room menu and notifications. */
object ChatRoomSettings {
    private fun preferences(context: Context, account: String) =
        context.getSharedPreferences("shareguard_chat_rooms_$account", Context.MODE_PRIVATE)

    fun isMuted(context: Context, account: String?, reservationId: String?): Boolean =
        account != null && reservationId != null && preferences(context, account).getBoolean("muted_$reservationId", false)

    fun isPinned(context: Context, account: String?, reservationId: String?): Boolean =
        account != null && reservationId != null && preferences(context, account).getBoolean("pinned_$reservationId", false)

    fun setMuted(context: Context, account: String, reservationId: String, muted: Boolean) {
        preferences(context, account).edit().putBoolean("muted_$reservationId", muted).apply()
    }

    fun setPinned(context: Context, account: String, reservationId: String, pinned: Boolean) {
        preferences(context, account).edit().putBoolean("pinned_$reservationId", pinned).apply()
    }

    fun clear(context: Context, account: String, reservationId: String) {
        preferences(context, account).edit().remove("muted_$reservationId").remove("pinned_$reservationId").apply()
    }
}
