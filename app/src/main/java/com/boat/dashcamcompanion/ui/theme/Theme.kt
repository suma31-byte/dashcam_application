package com.boat.dashcamcompanion.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// boAt Hive's CTAs are fully-rounded red "pill" buttons (see reference app), not the
// default Material3 rounded-rectangle. Bumping extraLarge close to a stadium shape
// makes every full-width Button/OutlinedButton in the app match that look automatically.
val BoatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = BoatRed80,
    secondary = BoatOrange80,
    tertiary = BoatGreen80
)

private val LightColorScheme = lightColorScheme(
    primary = BoatRed40,
    secondary = BoatOrange40,
    tertiary = BoatGreen40
)

@Composable
fun DashcamCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // boAt's red brand palette should stay consistent regardless of the phone's
    // wallpaper-derived Material You colors, so dynamic color defaults off here.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = BoatShapes,
        content = content
    )
}
