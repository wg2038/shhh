package io.github.wg2038.shhh.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wg2038.shhh.AppIcons
import io.github.wg2038.shhh.R
import io.github.wg2038.shhh.ui.theme.ExpressiveMotionTokens

@Composable
fun ExpressiveFloatingToolbar(
    autoStartEnabled: Boolean,
    autoLockEnabled: Boolean,
    onToggleAutoStart: (Boolean) -> Unit,
    onToggleAutoLock: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIconToggle(
                icon = AppIcons.AutoMode,
                description = stringResource(R.string.toolbar_autostart),
                checked = autoStartEnabled,
                onToggle = { onToggleAutoStart(!autoStartEnabled) }
            )

            ToolbarIconToggle(
                icon = AppIcons.Lock,
                description = stringResource(R.string.toolbar_autolock),
                checked = autoLockEnabled,
                onToggle = { onToggleAutoLock(!autoLockEnabled) }
            )

            ToolbarIconAction(
                icon = AppIcons.Info,
                description = stringResource(R.string.toolbar_about),
                onClick = onOpenAbout
            )
        }
    }
}

@Composable
private fun ToolbarIconToggle(
    icon: ImageVector,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    ToolbarIconSurface(
        icon = icon,
        description = description,
        selected = checked,
        isPressed = isPressed,
        modifier = Modifier.toggleable(
            value = checked,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Switch,
            onValueChange = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
        )
    )
}

@Composable
private fun ToolbarIconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    ToolbarIconSurface(
        icon = icon,
        description = description,
        selected = false,
        isPressed = isPressed,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
        )
    )
}

@Composable
private fun ToolbarIconSurface(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    isPressed: Boolean,
    modifier: Modifier = Modifier
) {
    // Keep resting state concentric (CircleShape) inside the pill toolbar,
    // and apply M3 Expressive squircle shape morphing only while pressed.
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 24.dp,
        animationSpec = ExpressiveMotionTokens.fastSpatial(),
        label = "toolbarIconCorner"
    )

    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = ExpressiveMotionTokens.fastEffects(),
        label = "toolbarIconBg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = ExpressiveMotionTokens.fastEffects(),
        label = "toolbarIconContent"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(cornerRadius.coerceAtLeast(0.dp)))
            .background(containerColor)
            .semantics { contentDescription = description }
            .then(modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
    }
}
