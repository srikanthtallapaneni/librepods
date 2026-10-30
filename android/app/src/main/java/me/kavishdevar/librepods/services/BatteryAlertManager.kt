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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus

/**
 * iOS-style battery alerts: a notification when a bud or the case drops to 20% and again at 10%,
 * and one when the case finishes charging while the buds are out.
 */
class BatteryAlertManager(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    // Lowest threshold already alerted per component, so each threshold fires once per discharge.
    private val alertedThreshold = mutableMapOf<Int, Int>()
    private var caseChargingBelowFull = false

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.battery_alerts_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun onBatteryUpdate(batteries: List<Battery>) {
        for (battery in batteries) {
            if (battery.status == BatteryStatus.DISCONNECTED || battery.level !in 1..100) continue
            val charging = battery.status == BatteryStatus.CHARGING ||
                battery.status == BatteryStatus.OPTIMIZED_CHARGING

            if (charging) {
                if (alertedThreshold.remove(battery.component) != null) {
                    notificationManager.cancel(LOW_BATTERY_NOTIFICATION_BASE + battery.component)
                }
            } else {
                checkLowBattery(battery)
            }

            if (battery.component == BatteryComponent.CASE) checkCaseFull(battery, charging)
        }
    }

    private fun checkLowBattery(battery: Battery) {
        val threshold = LOW_BATTERY_THRESHOLDS.lastOrNull { battery.level <= it }
        if (threshold == null) {
            // Small margin so a level flickering around 20% doesn't alert repeatedly.
            if (battery.level > RESET_LEVEL) alertedThreshold.remove(battery.component)
            return
        }
        val previous = alertedThreshold[battery.component]
        if (previous != null && previous <= threshold) return

        val title = when (battery.component) {
            BatteryComponent.LEFT -> R.string.battery_low_left
            BatteryComponent.RIGHT -> R.string.battery_low_right
            BatteryComponent.CASE -> R.string.battery_low_case
            else -> return
        }
        alertedThreshold[battery.component] = threshold
        notify(
            LOW_BATTERY_NOTIFICATION_BASE + battery.component,
            context.getString(title),
            context.getString(R.string.battery_level_remaining, battery.level)
        )
    }

    private fun checkCaseFull(battery: Battery, charging: Boolean) {
        if (battery.level >= 100) {
            if (caseChargingBelowFull) {
                notify(
                    CASE_FULL_NOTIFICATION_ID,
                    context.getString(R.string.case_fully_charged),
                    context.getString(R.string.battery_level_remaining, battery.level)
                )
            }
            caseChargingBelowFull = false
        } else {
            caseChargingBelowFull = charging
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "battery_alerts"
        private const val LOW_BATTERY_NOTIFICATION_BASE = 100
        private const val CASE_FULL_NOTIFICATION_ID = 110
        private const val RESET_LEVEL = 25
        private val LOW_BATTERY_THRESHOLDS = listOf(20, 10)
    }
}
