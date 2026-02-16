package net.spora.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.spora.android.ui.theme.Sage
import net.spora.android.ui.theme.SageDark
import net.spora.android.ui.theme.Slate
import net.spora.android.ui.theme.TextLight
import net.spora.android.ui.theme.TextMain
import net.spora.android.ui.theme.TextMuted
import kotlin.random.Random

@Composable
fun BarcodeStrip(
    modifier: Modifier = Modifier,
    lineCount: Int = 50,
    lineColor: Color = TextMain,
    alpha: Float = 0.3f,
    animate: Boolean = true,
) {
    var seed by remember { mutableStateOf(Random.nextInt()) }

    if (animate) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                seed = Random.nextInt()
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        val random = Random(seed)
        val lineWidth = 1.dp.toPx()
        val gap = 2.dp.toPx()
        val totalWidth = lineCount * (lineWidth + gap)
        val startX = (size.width - totalWidth) / 2f

        for (i in 0 until lineCount) {
            val heightFraction = when {
                random.nextFloat() > 0.8f -> 1f
                random.nextFloat() > 0.5f -> 0.6f
                else -> 0.3f
            }
            val lineHeight = size.height * heightFraction
            val x = startX + i * (lineWidth + gap)

            drawRect(
                color = lineColor.copy(alpha = alpha),
                topLeft = Offset(x, size.height - lineHeight),
                size = Size(lineWidth, lineHeight),
            )
        }
    }
}

@Composable
fun MiniBarcodeStrip(
    modifier: Modifier = Modifier,
    lineCount: Int = 20,
    lineColor: Color = TextMuted,
) {
    var seed by remember { mutableStateOf(Random.nextInt()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            seed = Random.nextInt()
        }
    }

    Canvas(
        modifier = modifier
            .width((lineCount * 3).dp)
            .height(20.dp),
    ) {
        val random = Random(seed)
        val lineWidth = 1.dp.toPx()
        val gap = 2.dp.toPx()

        for (i in 0 until lineCount) {
            val heightFraction = random.nextFloat()
            val lineHeight = size.height * heightFraction
            val x = i * (lineWidth + gap)

            drawRect(
                color = lineColor.copy(alpha = 0.5f),
                topLeft = Offset(x, size.height - lineHeight),
                size = Size(lineWidth, lineHeight),
            )
        }
    }
}

@Composable
fun SporaToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackColor = if (checked) Slate else SageDark
    val thumbColor = TextLight
    val trackAlpha = if (enabled) 1f else 0.5f

    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .background(
                color = trackColor.copy(alpha = trackAlpha),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .offset(
                    x = if (checked) 18.dp else 2.dp,
                    y = 2.dp,
                )
                .background(
                    color = thumbColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                ),
        )
    }
}

@Composable
fun ModalOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Sage.copy(alpha = 0.95f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                content()
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Slate.copy(alpha = alpha),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = TextLight,
            )
            if (trailingIcon != null) {
                trailingIcon()
            }
        }
    }
}

@Composable
fun StatusHeader(
    label: String,
    value: String,
    unit: String,
    periodLabel: String,
    modifier: Modifier = Modifier,
    showBarcode: Boolean = true,
) {
    val borderColor = TextMain

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(bottom = 20.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displayLarge,
                    color = TextMain,
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    color = TextMain.copy(alpha = 0.6f),
                )
            }
            Text(
                text = periodLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
        if (showBarcode) {
            Spacer(modifier = Modifier.height(4.dp))
            BarcodeStrip()
        }
    }
}

@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val borderColor = TextMain
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = TextMain),
            singleLine = true,
            cursorBrush = SolidColor(TextMain),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .drawBehind {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(vertical = 12.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextMuted.copy(alpha = 0.5f),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
fun CancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "CANCEL",
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
    )
}
