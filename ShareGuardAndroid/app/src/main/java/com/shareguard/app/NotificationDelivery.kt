package com.shareguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shareguard.app.data.AuthResult
import com.shareguard.app.data.SupabaseRestClient

object SessionTokens {
    @Synchronized
    fun refresh(context: Context, client: SupabaseRestClient): AuthResult {
        val prefs = context.getSharedPreferences("shareguard_session", Context.MODE_PRIVATE)
        val user = prefs.getString("user_id", null) ?: error("로그인이 필요합니다.")
        val token = prefs.getString("access_token", null)
        val expiry = prefs.getLong("expires_at", 0)
        if (token != null && expiry > System.currentTimeMillis() + 60_000) return AuthResult(token, prefs.getString("refresh_token", null), user, expiry, "")
        val result = client.refreshSession(prefs.getString("refresh_token", null) ?: error("다시 로그인해주세요."))
        if (result.isSignedIn && prefs.getString("user_id", null) == user && result.userId == user) {
            prefs.edit().putString("access_token", result.accessToken).putString("refresh_token", result.refreshToken)
                .putLong("expires_at", result.expiresAt).apply()
        }
        return result
    }
}

object NotificationDelivery {
    const val JOB_ID = 41005
    const val CHANNEL = "shareguard_activity"
    const val EXTRA_RESERVATION = "notification_reservation"
    const val EXTRA_KIND = "notification_kind"

    fun schedule(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "채팅 및 거래 알림", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, NotificationJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build()
        )
    }

    @Synchronized
    fun sync(context: Context, client: SupabaseRestClient, token: String, userId: String) {
        val session = context.getSharedPreferences("shareguard_session", Context.MODE_PRIVATE)
        if (session.getString("user_id", null) != userId) return
        val state = context.getSharedPreferences("notification_delivery_$userId", Context.MODE_PRIVATE)
        val after = if (state.contains("last_id")) state.getLong("last_id", 0) else null
        val events = client.fetchNotifications(token, after)
        if (session.getString("user_id", null) != userId) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return // Keep events pending until permission is granted.
        val settings = context.getSharedPreferences("shareguard_settings", Context.MODE_PRIVATE)
        events.filter { event ->
            event.unread && settings.getBoolean(
                if (event.kind == "chat") "chat_notifications" else "trade_notifications", true
            ) && (event.kind != "chat" || !ChatRoomSettings.isMuted(context, userId, event.reservationId))
        }.takeLast(10).forEach { event ->
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("shareguard://notification/${event.id}"))
                .putExtra(EXTRA_RESERVATION, event.reservationId).putExtra(EXTRA_KIND, event.kind)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify("$userId:${event.id}", 0, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(event.title).setContentText(event.body)
                .setStyle(Notification.BigTextStyle().bigText(event.body)).setContentIntent(pending)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        }
        events.maxOfOrNull { it.id }?.let { state.edit().putLong("last_id", it).apply() }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
}

class NotificationJobService : JobService() {
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            val result = runCatching {
                val prefs = getSharedPreferences("shareguard_session", MODE_PRIVATE)
                val user = prefs.getString("user_id", null) ?: return@runCatching
                if (System.currentTimeMillis() - prefs.getLong("last_active_at", 0) >= 14L * 24 * 60 * 60 * 1000) return@runCatching
                val client = SupabaseRestClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                val auth = SessionTokens.refresh(this, client)
                if (!stopped && auth.isSignedIn && auth.userId == user) NotificationDelivery.sync(this, client, auth.accessToken!!, user)
            }
            if (!stopped) jobFinished(params, result.isFailure)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
}
