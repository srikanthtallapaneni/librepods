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

package me.kavishdevar.librepods.presentation.screens

import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListItem
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import java.io.File

@Composable
fun RecorderScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()
    val recorder by viewModel.recorderState.collectAsState()
    val recordings by viewModel.recordings.collectAsState()

    // Ticks the timer while recording.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recorder.isRecording) {
        while (recorder.isRecording) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<File?>(null) }
    fun stopPlayback() {
        player?.release()
        player = null
        playing = null
    }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    val bottomPadding = if (m3eEnabled) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .layerBackdrop(backdrop)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        Text(
            text = if (recorder.isRecording) {
                DateUtils.formatElapsedTime((now - recorder.startedAt).coerceAtLeast(0) / 1000)
            } else {
                stringResource(R.string.recorder_description)
            },
            style = if (recorder.isRecording) MaterialTheme.typography.displayMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (recorder.isRecording) FontWeight.Bold else null,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        )

        if (recorder.isRecording) {
            if (recorder.framesReceived == 0L) {
                Text(
                    text = stringResource(R.string.recorder_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(
                    progress = { recorder.level },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        StyledButton(
            onClick = {
                if (recorder.isRecording) {
                    viewModel.stopRecording()
                } else {
                    stopPlayback()
                    if (!viewModel.startRecording()) {
                        Toast.makeText(context, R.string.recorder_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(if (recorder.isRecording) R.string.recorder_stop else R.string.recorder_start),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        StyledList(title = stringResource(R.string.recorder_recordings)) {
            if (recordings.isEmpty()) {
                StyledListItem(name = stringResource(R.string.recorder_none), onClick = null)
            }
            recordings.forEach { file ->
                StyledListItem(
                    name = file.nameWithoutExtension,
                    description = "${DateUtils.getRelativeTimeSpanString(file.lastModified())} · " +
                        Formatter.formatShortFileSize(context, file.length()),
                    // Tap a recording to play it, tap again to stop.
                    onClick = {
                        val wasPlaying = playing == file
                        stopPlayback()
                        if (!wasPlaying && !recorder.isRecording) {
                            val mp = MediaPlayer()
                            try {
                                mp.setDataSource(file.path)
                                mp.setOnCompletionListener { stopPlayback() }
                                mp.prepare()
                                mp.start()
                                player = mp
                                playing = file
                            } catch (e: Exception) {
                                mp.release()
                                Toast.makeText(context, R.string.recorder_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (playing == file) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.recorder_play)
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                val share = Intent(Intent.ACTION_SEND)
                                    .setType("audio/wav")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(share, null))
                            }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.recorder_share))
                            }
                            IconButton(onClick = {
                                if (playing == file) stopPlayback()
                                viewModel.deleteRecording(file)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.recorder_delete))
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}
