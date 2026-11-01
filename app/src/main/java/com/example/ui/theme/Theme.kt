package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
      primary = PrimaryPurple,
      secondary = SecondaryPurple,
      tertiary = AccentPink,
      background = CharcoalDark,
      surface = SurfaceDark,
      surfaceVariant = CardDark,
      onBackground = OnDark,
      onSurface = OnDark
  )

private val LightColorScheme =
  lightColorScheme(
      primary = PrimaryPurpleDark,
      secondary = SecondaryPurpleDark,
      tertiary = AccentPink,
      background = WarmWhite,
      surface = SurfaceLight,
      surfaceVariant = CardLight,
      onBackground = OnLight,
      onSurface = OnLight
  )

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Default to custom theme for editorial look
    content: @Composable () -> Unit,
) {
  val colorScheme =
      when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
          val context = LocalContext.current
          if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
      }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
