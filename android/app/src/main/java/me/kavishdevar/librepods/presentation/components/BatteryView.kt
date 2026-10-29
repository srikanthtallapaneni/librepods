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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun BatteryView(
    batteryList: List<Battery>,
    budsRes: Int,
    caseRes: Int,
    engraving: String = ""
) {
    val left = batteryList.find { it.component == BatteryComponent.LEFT }
    val right = batteryList.find { it.component == BatteryComponent.RIGHT }
    val case = batteryList.find { it.component == BatteryComponent.CASE }

    val leftLevel = left?.level ?: 0
    val rightLevel = right?.level ?: 0
    val caseLevel = case?.level ?: 0

    val isSingleDisplay = left?.status == right?.status && (leftLevel - rightLevel) in -3..3

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 350.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = ImageBitmap.imageResource(budsRes),
                        contentDescription = stringResource(R.string.buds),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSingleDisplay) {
                        BatteryIndicator(
                            leftLevel.coerceAtMost(rightLevel),
                            left?.status ?: BatteryStatus.NOT_CHARGING
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (leftLevel > 0 || left?.status != BatteryStatus.DISCONNECTED) {
                                BatteryIndicator(
                                    leftLevel,
                                    left?.status ?: BatteryStatus.NOT_CHARGING,
                                    "\uDBC6\uDCE5"
                                )
                            }

                            if (leftLevel > 0 && rightLevel > 0) {
                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            if (rightLevel > 0 || right?.status != BatteryStatus.DISCONNECTED) {
                                BatteryIndicator(
                                    rightLevel,
                                    right?.status ?: BatteryStatus.NOT_CHARGING,
                                    "\uDBC6\uDCE8"
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Not BoxWithConstraints: the Row above asks for intrinsic heights, which a
                    // SubcomposeLayout can't answer, and that crashes the screen.
                    var caseWidthPx by remember { mutableIntStateOf(0) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { caseWidthPx = it.width }
                    ) {
                        Image(
                            bitmap = ImageBitmap.imageResource(caseRes),
                            contentDescription = stringResource(R.string.case_alt),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (engraving.isNotBlank() && caseWidthPx > 0) {
                            // Sized and placed from a real engraved case: just below the light,
                            // with letters about 5% of the case width tall.
                            CaseEngraving(
                                engraving = engraving,
                                fontSize = with(LocalDensity.current) { (caseWidthPx * 0.10f + 1f).toSp() },
                                modifier = Modifier.align(BiasAlignment(0f, 0.28f))
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (caseLevel > 0 || case?.status != BatteryStatus.DISCONNECTED) {
                        BatteryIndicator(
                            caseLevel,
                            case?.status ?: BatteryStatus.NOT_CHARGING,
                            prefix = if (!isSingleDisplay) "\uDBC3\uDE6C" else ""
                        )
                    }
                }
            }
        }
    }
}

// Apple engraves in grey, semibold SF Pro Rounded, and emoji come out uncoloured, so the text is
// drawn through a greyscale layer.
@Composable
private fun CaseEngraving(
    engraving: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val greyscale = remember {
        Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        }
    }
    Text(
        text = engraving,
        modifier = modifier
            .fillMaxWidth(0.75f)
            .drawWithContent {
                drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), greyscale) }
                drawContent()
                drawIntoCanvas { it.restore() }
            },
        style = TextStyle(
            fontSize = fontSize,
            fontFamily = FontFamily(Font(R.font.sf_pro_rounded, FontWeight.SemiBold)),
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFA2A2A4),
            letterSpacing = 0.06.em,
            textAlign = TextAlign.Center
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryViewPreview() {
    val fakeBattery = listOf(
        Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
        Battery(BatteryComponent.RIGHT, 40, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.CASE, 60, BatteryStatus.NOT_CHARGING)
    )

    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    LibrePodsTheme(m3eEnabled = false) {
        Box(
            modifier = Modifier
                .background(bg)
                .padding(16.dp)
        ) {
            BatteryView(
                batteryList = fakeBattery,
                budsRes = R.drawable.airpods_5_wireless_buds,
                caseRes = R.drawable.airpods_5_wireless_case,
                engraving = "LibrePods"
            )
        }
    }
}
