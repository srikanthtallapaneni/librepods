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

package me.kavishdevar.librepods

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.screens.onboarding.PrivacyPolicyPage
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme

/**
 * Health Connect requires an app that asks for health permissions to explain how it uses them.
 * It opens this activity from its permission screen and from "Manage permissions".
 */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val m3eEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("m3e_enabled", true)
        setContent {
            LibrePodsTheme(m3eEnabled = m3eEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .safeDrawingPadding()
                        .padding(16.dp)
                ) {
                    PrivacyPolicyPage(onForward = ::finish)
                }
            }
        }
    }
}
