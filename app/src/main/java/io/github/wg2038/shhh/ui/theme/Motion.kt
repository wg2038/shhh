package io.github.wg2038.shhh.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

object ExpressiveMotionTokens {

    fun <T> fastSpatial(): SpringSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = 1400f
    )

    fun <T> defaultSpatial(): SpringSpec<T> = spring(
        dampingRatio = 0.65f,
        stiffness = 800f
    )

    fun <T> fastEffects(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 1400f
    )

    fun <T> defaultEffects(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 800f
    )

    val FadeIn: SpringSpec<Float> = spring(Spring.DampingRatioNoBouncy, 800f)
    val FadeOut: SpringSpec<Float> = spring(Spring.DampingRatioNoBouncy, 1400f)
}
