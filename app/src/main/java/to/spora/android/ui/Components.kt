package to.spora.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import to.spora.android.R
import to.spora.android.ui.theme.BorderError
import to.spora.android.ui.theme.BorderHairline
import to.spora.android.ui.theme.BorderStrong
import to.spora.android.ui.theme.ColorAction
import to.spora.android.ui.theme.ColorError
import to.spora.android.ui.theme.ColorPendingText
import to.spora.android.ui.theme.ColorPrimary
import to.spora.android.ui.theme.ColorSuccessDot
import to.spora.android.ui.theme.DurationQuick
import to.spora.android.ui.theme.EaseStandard
import to.spora.android.ui.theme.Ember050
import to.spora.android.ui.theme.Ember900
import to.spora.android.ui.theme.Ink900
import to.spora.android.ui.theme.OnActionSubtitle
import to.spora.android.ui.theme.PressScale
import to.spora.android.ui.theme.Sage100
import to.spora.android.ui.theme.Sage200
import to.spora.android.ui.theme.Sage400
import to.spora.android.ui.theme.SurfaceBg
import to.spora.android.ui.theme.SurfaceCard
import to.spora.android.ui.theme.TextMuted
import to.spora.android.ui.theme.TextOnDark
import to.spora.android.ui.theme.TextPrimary

// Shape-coded status marker — states stay legible without color:
// ● filled circle = active · ○ ring = pending · ◆ diamond = error
enum class DotState { Active, Pending, Error, None }

@Composable
fun StatusDot(state: DotState, modifier: Modifier = Modifier) {
    when (state) {
        DotState.Active -> Box(
            modifier = modifier
                .size(7.dp)
                .background(ColorSuccessDot, CircleShape),
        )
        DotState.Pending -> Box(
            modifier = modifier
                .size(8.dp)
                .border(1.8.dp, ColorPendingText, CircleShape),
        )
        DotState.Error -> Box(
            modifier = modifier
                .size(7.dp)
                .rotate(45f)
                .background(ColorError, RoundedCornerShape(1.dp)),
        )
        DotState.None -> {}
    }
}

// The squared toggle — rectangular track, square thumb (brand invariant §3.1).
@Composable
fun SporaToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) ColorPrimary else Sage400,
        animationSpec = tween(DurationQuick, easing = EaseStandard),
        label = "toggleTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = tween(DurationQuick, easing = EaseStandard),
        label = "toggleThumb",
    )

    // The visual stays 40x22, but the interactive area meets the 48dp minimum
    // and announces itself as a switch with on/off state.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .background(trackColor, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffset.roundToPx(), 3.dp.roundToPx()) }
                    .size(16.dp)
                    .background(
                        color = if (checked) Sage200 else SurfaceCard,
                        shape = RoundedCornerShape(1.dp),
                    )
                    .then(
                        if (checked) Modifier
                        else Modifier.border(
                            1.dp,
                            Ink900.copy(alpha = 0.12f),
                            RoundedCornerShape(1.dp),
                        )
                    ),
            )
        }
    }
}

// A real dialog window: the system back gesture dismisses it, everything
// behind it (tabs, pager, toggles) is non-interactive and hidden from
// accessibility, and the content stays above the keyboard via imePadding.
// The scrim is solid surface-bg — content behind is not visible (design rule).
@Composable
fun ModalOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The scrim is already a solid brand surface; the window's default dim
        // would grey it (and the status bar) down.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0f)
            dialogWindow?.let { w ->
                WindowCompat.getInsetsController(w, w.decorView)
                    .isAppearanceLightStatusBars = true
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 26.dp, vertical = 24.dp)
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

// Press feedback: scale 0.98 over 150ms (design motion spec).
private fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier =
    composed {
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) PressScale else 1f,
            animationSpec = tween(DurationQuick, easing = EaseStandard),
            label = "pressScale",
        )
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingArrow: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(10.dp))
            .background(ColorPrimary)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = TextOnDark,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailingArrow) {
            Icon(
                imageVector = SporaIcons.ArrowRight,
                contentDescription = null,
                tint = TextOnDark,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// Quiet full-width text action (cancel / skip) — mono micro-label style.
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 18.dp),
    )
}

@Composable
fun CancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextActionButton(
        text = stringResource(R.string.action_cancel),
        onClick = onClick,
        modifier = modifier,
    )
}

// Underline-only input (brand invariant §3.1): mono micro-label, 21sp text,
// ink underline that turns rust when `error` is set.
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    focusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        val underlineColor = if (error != null) ColorError else TextPrimary
        val underlineWidth = if (error != null) 2.dp else 1.5.dp
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = TextPrimary),
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            cursorBrush = SolidColor(TextPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                )
                .drawBehind {
                    drawLine(
                        color = underlineColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = underlineWidth.toPx(),
                    )
                }
                .padding(top = 8.dp, bottom = 6.dp),
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
        if (error != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                StatusDot(DotState.Error)
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorError,
                )
            }
        } else if (helper != null) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// Hero action card — the tab's primary call to action.
// Orange = act (and nothing else); errors are rust, never orange.
@Composable
fun SporaActionCard(
    eyebrow: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color(0xFF96371E).copy(alpha = 0.5f),
                spotColor = Color(0xFF96371E).copy(alpha = 0.5f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(ColorAction)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = !busy,
                onClick = onClick,
            )
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)
            .defaultMinSize(minHeight = 112.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp,
                ),
                color = Ember900,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = Ember050,
                modifier = Modifier
                    .padding(top = 9.dp)
                    .fillMaxWidth(0.85f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                color = OnActionSubtitle,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = Ember050,
                        trackColor = Color.White.copy(alpha = 0.35f),
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    // The signature −45° circle-arrow
                    Icon(
                        imageVector = SporaIcons.ArrowRight,
                        contentDescription = null,
                        tint = Ember050,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(-45f),
                    )
                }
            }
        }
    }
}

// 48dp-target icon button with a small stroke glyph.
@Composable
fun SmallIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextMuted,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

// The barcode strip — Spora's data-viz language. Deterministic (seeded),
// 2dp bars at 3 quantized heights. Pure activity texture, aria-hidden.
@Composable
fun Barcode(
    seed: Int,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 18.dp,
    color: Color = ColorSuccessDot,
    opacity: Float = 0.75f,
) {
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val barWidth = 2.dp.toPx()
        val gap = 2.dp.toPx()
        val bars = ((size.width + gap) / (barWidth + gap)).toInt()
        var t = seed
        for (i in 0 until bars) {
            // mulberry32 — matches the design system's deterministic strips
            t += 0x6D2B79F5.toInt()
            var r = t xor (t ushr 15)
            r *= (1 or t)
            r = r xor (r + ((r xor (r ushr 7)) * (61 or r)))
            val v = ((r xor (r ushr 14)).toUInt().toDouble() / 4294967296.0)
            val h = when {
                v > 0.82 -> size.height
                v > 0.5 -> size.height * 0.6f
                else -> size.height * 0.3f
            }
            drawRoundRect(
                color = color,
                alpha = opacity,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

// Empty list state: dashed hairline card with a muted mark.
@Composable
fun EmptyStateCard(
    title: String,
    body: String,
    logo: Painter,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = BorderStrong,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                        ),
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            }
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = logo,
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TextMuted),
            modifier = Modifier
                .height(32.dp)
                .alpha(0.45f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                fontSize = 13.sp,
            ),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// Shared list item for both tabs — solid card, hairline border, shape-coded
// status, squared toggle; a barcode activity strip on live share items.
@Composable
fun ConnectionCard(
    name: String,
    dotState: DotState,
    statusText: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    url: String? = null,
    errorText: String? = null,
    statusColor: Color = TextMuted,
    showBarcode: Boolean = false,
    barcodeSeed: Int = 11,
    onShareAgain: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val border = if (errorText != null) BorderError else BorderHairline
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCard)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = if (showBarcode) 10.dp else 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.displayMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (url != null) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (errorText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 5.dp),
                    ) {
                        StatusDot(DotState.Error)
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorError,
                        )
                    }
                } else if (statusText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 5.dp),
                    ) {
                        StatusDot(dotState)
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (onShareAgain != null) {
                    SmallIconButton(
                        icon = SporaIcons.ShareAgain,
                        contentDescription = stringResource(R.string.share_link_content_desc),
                        onClick = onShareAgain,
                    )
                }
                if (onDelete != null) {
                    SmallIconButton(
                        icon = SporaIcons.Trash,
                        contentDescription = stringResource(R.string.delete_connection_content_desc),
                        onClick = onDelete,
                    )
                }
                SporaToggle(
                    checked = checked,
                    onCheckedChange = onToggle,
                )
            }
        }
        if (showBarcode) {
            Barcode(seed = barcodeSeed)
        }
    }
}
