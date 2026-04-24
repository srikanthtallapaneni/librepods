package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R


@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayBypassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    backdrop: LayerBackdrop
) {
    if (!visible) return

    val dark = isSystemInDarkTheme()
    val contentColor = if (dark) Color.White else Color.Black

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var acknowledged by remember { mutableStateOf(false) }
    val inputState = rememberTextFieldState("")

    val isValid = acknowledged && inputState.text.trim() == "OK"

    val sfPro = FontFamily(Font(R.font.sf_pro))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = { },
        shape = RoundedCornerShape(48.dp),
        scrimColor = Color.Transparent,
        modifier = Modifier.padding(16.dp)
    ) {
        val innerBackdrop = rememberLayerBackdrop()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(48.dp))
                .drawBackdrop(
                    backdrop = backdrop,
                    exportedBackdrop = innerBackdrop,
                    shape = { RoundedCornerShape(48.dp) },
                    effects = {
                        vibrancy()
                        blur(6f.dp.toPx())
                        lens(12f.dp.toPx(), 48f.dp.toPx(), true)
                    },
                    onDrawSurface = {
                        drawRect(
                            if (dark) Color.DarkGray.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.6f)
                        )
                    }
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.bypass_compatibility_check),
                    style = TextStyle(
                        fontFamily = sfPro,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = contentColor
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Text(
                    text = stringResource(R.string.compatibility_play_dialog_confirmation),
                    style = TextStyle(
                        fontFamily = sfPro,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = contentColor
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                StyledSelectList(
                    items = listOf(
                        SelectItem(
                            name = stringResource(R.string.read_compatibility_requirements),
                            selected = acknowledged,
                            onClick = { acknowledged = !acknowledged }
                        )
                    )
                )

                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                val backgroundColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
                val textColor = if (dark) Color.White else Color.Black
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(
                            backgroundColor,
                            RoundedCornerShape(28.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        state = inputState,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            color = textColor,
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        ),
                        cursorBrush = SolidColor(textColor),
                        decorator = { innerTextField ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                ) {
                                    Box {
                                        if (inputState.text == "") {
                                            Text(
                                                text = stringResource(R.string.type_ok_to_continue, "OK"),
                                                style = TextStyle(
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Light,
                                                    fontFamily = sfPro,
                                                    color = textColor.copy(alpha = 0.8f)
                                                )
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        inputState.clearText()
                                    }
                                ) {
                                    Text(
                                        text = "􀁡",
                                        style = TextStyle(
                                            fontSize = 16.sp,
                                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                                            color = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)
                                        ),
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp)
                            .focusRequester(focusRequester)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StyledButton(
                        onClick = onDismiss,
                        backdrop = innerBackdrop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.no),
                            style = TextStyle(
                                fontFamily = sfPro,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = contentColor
                            )
                        )
                    }
                    StyledButton(
                        onClick =  onConfirm,
                        backdrop = innerBackdrop,
                        isInteractive = isValid,
                        modifier = Modifier.weight(1f),
                        enabled = isValid,
                        surfaceColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
                    ) {
                        Text(
                            text = stringResource(R.string.proceed),
                            style = TextStyle(
                                fontFamily = sfPro,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = if (isValid) contentColor else contentColor.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }
        }
    }
}
