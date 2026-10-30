/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import me.kavishdevar.librepods.R

/**
 * Feeds "Announce Notifications" and "Announce Calls": reads new messages and incoming callers
 * aloud through [AirPodsService.announce] while the AirPods are worn.
 */
class NotificationAnnouncerService : NotificationListenerService() {
    // Messaging apps repost the same notification when it is updated or read elsewhere.
    private val recentAnnouncements = ArrayDeque<String>()
    private val announcedCalls = mutableSetOf<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val service = ServiceManager.getService() ?: return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val notification = sbn.notification

        if (notification.category == Notification.CATEGORY_CALL) {
            if (prefs.getBoolean("announce_calls", false)) announceCall(sbn, service)
            return
        }

        if (!prefs.getBoolean("announce_notifications", false)) return
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        if (!isAudible(sbn)) return

        val text = messageText(notification) ?: return
        val dedupeKey = "${sbn.key}|$text"
        if (dedupeKey in recentAnnouncements) return
        recentAnnouncements.addLast(dedupeKey)
        if (recentAnnouncements.size > MAX_RECENT) recentAnnouncements.removeFirst()

        service.announce(text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        announcedCalls.remove(sbn.key)
    }

    // Skips silent conversations and anything Do Not Disturb would hide.
    private fun isAudible(sbn: StatusBarNotification): Boolean {
        val ranking = NotificationListenerService.Ranking()
        if (!currentRanking.getRanking(sbn.key, ranking)) return true
        return ranking.matchesInterruptionFilter() &&
            ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun messageText(notification: Notification): String? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            val message = style.messages.lastOrNull() ?: return null
            // A message without a person (or with the user's own) is one the user sent, for
            // example a reply from the notification, which apps repost with the reply appended.
            val person = message.person ?: return null
            if (person.key != null && person.key == style.user.key) return null
            val body = message.text?.toString()?.trim()?.take(MAX_LENGTH) ?: return null
            val sender = person.name?.toString()
            val conversation = style.conversationTitle?.toString()
            return when {
                style.isGroupConversation && sender != null && conversation != null ->
                    getString(R.string.announce_group_message, sender, conversation, body)
                sender != null -> getString(R.string.announce_message, sender, body)
                conversation != null -> getString(R.string.announce_message, conversation, body)
                else -> body
            }
        }

        if (notification.category != Notification.CATEGORY_MESSAGE) return null
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.trim()?.take(MAX_LENGTH) ?: return null
        return if (title != null) getString(R.string.announce_message, title, body) else body
    }

    private fun announceCall(sbn: StatusBarNotification, service: AirPodsService) {
        val extras = sbn.notification.extras
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        // Dialers that don't use CallStyle: fall back to the ringing audio mode.
        val ringing = callType == Notification.CallStyle.CALL_TYPE_INCOMING ||
            (callType == 0 && getSystemService(AudioManager::class.java).mode == AudioManager.MODE_RINGTONE)
        if (!ringing || !announcedCalls.add(sbn.key)) return

        val caller = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        service.announce(
            if (caller.isNullOrBlank()) getString(R.string.announce_call_unknown)
            else getString(R.string.announce_call, caller)
        )
    }

    companion object {
        private const val MAX_RECENT = 20
        private const val MAX_LENGTH = 300
    }
}
