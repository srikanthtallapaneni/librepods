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

package me.kavishdevar.librepods.data

import android.content.SharedPreferences
import android.icu.text.BreakIterator
import androidx.core.content.edit

/**
 * The case engraving. AirPods don't send it over Bluetooth, so the user types it in, and it's
 * stored per AirPods (by MAC address) so a second pair doesn't show the first pair's engraving.
 */
object Engraving {
    // Apple fits about two lines of ~15 characters on the case; longer text shrinks until unreadable.
    const val MAX_LENGTH = 30

    // Used before engravings were stored per device, and when no AirPods have connected yet.
    private const val LEGACY_KEY = "engraving"

    private fun key(prefs: SharedPreferences): String =
        prefs.getString("mac_address", "")?.takeIf { it.isNotEmpty() }?.let { "${LEGACY_KEY}_$it" }
            ?: LEGACY_KEY

    fun read(prefs: SharedPreferences): String {
        val key = key(prefs)
        prefs.getString(key, null)?.let { return it }
        val legacy = prefs.getString(LEGACY_KEY, "") ?: ""
        // Hand an engraving saved before per-device storage to the AirPods connected now.
        if (key != LEGACY_KEY && legacy.isNotEmpty()) {
            prefs.edit {
                putString(key, legacy)
                remove(LEGACY_KEY)
            }
        }
        return legacy
    }

    fun write(prefs: SharedPreferences, engraving: String) {
        prefs.edit { putString(key(prefs), cap(engraving.trim())) }
    }

    /**
     * [text] cut to at most [MAX_LENGTH] UTF-16 units, on a character boundary so an emoji (a
     * surrogate pair or a ZWJ sequence) is never split in half.
     */
    fun cap(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        val end = boundaries.preceding(MAX_LENGTH + 1).coerceAtLeast(0)
        return text.substring(0, end)
    }

    /** Whether a change to [key] can change what [read] returns. */
    fun affects(key: String?): Boolean = key == "mac_address" || key?.startsWith(LEGACY_KEY) == true
}
