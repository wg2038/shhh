package io.github.wg2038.shhh.ui.components

import android.graphics.Path as AndroidPath
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.toPath
import io.github.wg2038.shhh.AppIcons
import io.github.wg2038.shhh.R
import io.github.wg2038.shhh.ui.theme.ExpressiveMotionTokens
import io.github.wg2038.shhh.ui.theme.M3ExpressiveShapes
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ExpressiveHeroCenterpiece(
    isRunning: Boolean,
    isFlippedDown: Boolean,
    isDndActive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    heroSize: Dp = 260.dp,
) {
    val haptic = LocalHapticFeedback.current
    val heroActive = isRunning && isFlippedDown && isDndActive
    val heroDescription = stringResource(R.string.app_name)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = ExpressiveMotionTokens.fastSpatial(),
        label = "heroButtonScale"
    )

    val morphSequence = remember { M3ExpressiveShapes.buildMorphSequence() }
    val shapesScaleFactor = remember {
        M3ExpressiveShapes.calculateScaleFactor(M3ExpressiveShapes.sequence) * 0.92f
    }

    val morphProgress = remember { Animatable(0f) }
    var morphRotationTargetAngle by remember { mutableFloatStateOf(90f) }
    val globalRotation = remember { Animatable(0f) }
    var currentMorphIndex by remember { mutableIntStateOf(0) }

    // Only morph and rotate while the service is active; pause cleanly at the current angle when off.
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect

        val morphSpec = spring<Float>(
            dampingRatio = 0.88f,
            stiffness = 180f,
            visibilityThreshold = 0.005f
        )

        launch {
            while (true) {
                val deferred = async {
                    val result = morphProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = morphSpec
                    )
                    if (result.endReason == AnimationEndReason.Finished) {
                        morphProgress.snapTo(0f)
                        currentMorphIndex = (currentMorphIndex + 1) % morphSequence.size
                        morphRotationTargetAngle = (morphRotationTargetAngle + 90f) % 360f
                    }
                }
                delay(850L)
                deferred.await()
            }
        }

        launch {
            while (true) {
                val current = globalRotation.value % 360f
                globalRotation.snapTo(current)
                val remainingMs = (5600f * ((360f - current) / 360f)).toInt().coerceAtLeast(1)
                globalRotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = remainingMs, easing = LinearEasing)
                )
                globalRotation.snapTo(0f)
            }
        }
    }

    val cookieFillColor by animateColorAsState(
        targetValue = when {
            heroActive -> MaterialTheme.colorScheme.tertiary
            isRunning -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = ExpressiveMotionTokens.defaultEffects(),
        label = "cookieFillColor"
    )

    val iconColor by animateColorAsState(
        targetValue = when {
            heroActive -> MaterialTheme.colorScheme.onTertiary
            isRunning -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = ExpressiveMotionTokens.fastEffects(),
        label = "heroIconColor"
    )

    val heroIcon = when {
        heroActive -> AppIcons.Bedtime
        isRunning -> AppIcons.DoNotDisturbOn
        else -> AppIcons.PowerSettingsNew
    }

    val androidPath = remember { AndroidPath() }
    val composePath = remember(androidPath) { androidPath.asComposePath() }
    val scaleMatrix = remember { Matrix() }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(heroSize)
                .semantics { contentDescription = heroDescription }
                .toggleable(
                    value = isRunning,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Switch,
                    onValueChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(heroSize)) {
                val centerPt = this.center

                val progress = morphProgress.value.coerceIn(0f, 1f)
                val step = morphSequence[currentMorphIndex]
                step.morph.toPath(
                    progress = progress,
                    path = androidPath
                )
                M3ExpressiveShapes.processPath(
                    path = composePath,
                    size = size,
                    scaleFactor = shapesScaleFactor,
                    matrix = scaleMatrix,
                    normalizedCenter = step.centerAt(progress),
                )

                val currentAngle = (progress * 90f + morphRotationTargetAngle + globalRotation.value) % 360f

                rotate(degrees = currentAngle, pivot = centerPt) {
                    drawPath(
                        path = composePath,
                        color = cookieFillColor,
                        style = Fill
                    )
                }
            }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = heroIcon,
                    transitionSpec = {
                        (scaleIn(animationSpec = ExpressiveMotionTokens.fastSpatial(), initialScale = 0.75f) +
                                fadeIn(animationSpec = ExpressiveMotionTokens.fastEffects()))
                            .togetherWith(
                                scaleOut(animationSpec = ExpressiveMotionTokens.fastEffects(), targetScale = 0.75f) +
                                        fadeOut(animationSpec = ExpressiveMotionTokens.fastEffects())
                            )
                    },
                    label = "heroIconTransition"
                ) { targetIcon ->
                    Icon(
                        imageVector = targetIcon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(heroSize * (80f / 260f))
                    )
                }
            }
        }
    }
}
