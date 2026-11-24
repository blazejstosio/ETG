package org.etg.migration.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

enum class EtgThemePreference {
    DARK,
    LIGHT,
    SYSTEM
}

enum class EtgLanguagePreference {
    SYSTEM,
    PL,
    EN
}

@Immutable
data class GrapheneColors(
    val isDark: Boolean,
    val background: Color,
    val backgroundGlow: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceHigh: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentCyan: Color,
    val accentStrong: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val verifiedEmerald: Color,
    val emeraldStrong: Color,
    val onEmerald: Color,
    val warningAmber: Color,
    val onWarning: Color,
    val errorRed: Color,
    val onError: Color
)

private val GrapheneDarkColors = GrapheneColors(
    isDark = true,
    background = Color(0xFF121212),
    backgroundGlow = Color(0x00000000),
    surface = Color(0xFF1C1C1C),
    surfaceContainer = Color(0xFF212121),
    surfaceHigh = Color(0xFF2E2E2E),
    border = Color(0xFF2C2C2C),
    borderStrong = Color(0xFF444444),
    textPrimary = Color(0xDEFFFFFF),
    textSecondary = Color(0x99FFFFFF),
    textTertiary = Color(0x73FFFFFF),
    accentCyan = Color(0xFF90CAF9),
    accentStrong = Color(0xFF95C4FF),
    accentContainer = Color(0xFF3E4758),
    onAccent = Color(0xFF000000),
    verifiedEmerald = Color(0xFF81C995),
    emeraldStrong = Color(0xFF81C995),
    onEmerald = Color(0xFF000000),
    warningAmber = Color(0xFFFCBD00),
    onWarning = Color(0xFF000000),
    errorRed = Color(0xFFCF6679),
    onError = Color(0xFF000000)
)

private val GrapheneLightColors = GrapheneColors(
    isDark = false,
    background = Color(0xFFFAFAFA),
    backgroundGlow = Color(0x00000000),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceHigh = Color(0xFFE6E6E6),
    border = Color(0xFFDDDDDD),
    borderStrong = Color(0xFFC4C6CF),
    textPrimary = Color(0xDE000000),
    textSecondary = Color(0x99000000),
    textTertiary = Color(0x73000000),
    accentCyan = Color(0xFF1565C0),
    accentStrong = Color(0xFF0053A3),
    accentContainer = Color(0xFFDAE2F9),
    onAccent = Color(0xFFFFFFFF),
    verifiedEmerald = Color(0xFF2E7D32),
    emeraldStrong = Color(0xFF2E7D32),
    onEmerald = Color(0xFFFFFFFF),
    warningAmber = Color(0xFF8A4B00),
    onWarning = Color(0xFFFFFFFF),
    errorRed = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF)
)

val GrapheneMenuBar = Color(0xFF212121)
val GrapheneMenuBarActive = Color(0xFF64B5F6)

val LocalGrapheneColors = staticCompositionLocalOf { GrapheneDarkColors }

object EtgDimens {
    val screenGutter = 20.dp
    val sectionGap = 14.dp
    val cardPadding = 16.dp
    val cardRadius = 12.dp
    val innerRadius = 10.dp
    val controlRadius = 8.dp
    val chipRadius = 8.dp
    val buttonHeightLarge = 52.dp
    val buttonHeightMedium = 46.dp
    val buttonHeightSmall = 38.dp
}

val MonoTechnicalStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 11.5.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
    fontFeatureSettings = "tnum"
)

val NumericStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.5.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum"
)

val MonoMetricStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.1.sp,
    fontFeatureSettings = "tnum"
)

private val EtgTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.75.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.05.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.6.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.1.sp
    )
)

private val EtgShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EtgTheme(
    themePreference: EtgThemePreference,
    languagePreference: EtgLanguagePreference,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themePreference) {
        EtgThemePreference.DARK -> true
        EtgThemePreference.LIGHT -> false
        EtgThemePreference.SYSTEM -> systemDark
    }
    val gColors = if (useDark) GrapheneDarkColors else GrapheneLightColors

    val materialScheme = if (useDark) {
        darkColorScheme(
            primary = gColors.accentCyan,
            onPrimary = gColors.onAccent,
            primaryContainer = gColors.accentContainer,
            onPrimaryContainer = gColors.accentCyan,
            secondary = gColors.verifiedEmerald,
            onSecondary = gColors.onEmerald,
            background = gColors.background,
            onBackground = gColors.textPrimary,
            surface = gColors.surface,
            onSurface = gColors.textPrimary,
            surfaceVariant = gColors.surfaceContainer,
            onSurfaceVariant = gColors.textSecondary,
            surfaceContainerLow = gColors.surface,
            surfaceContainer = gColors.surfaceContainer,
            surfaceContainerHigh = gColors.surfaceHigh,
            outline = gColors.borderStrong,
            outlineVariant = gColors.border,
            error = gColors.errorRed,
            onError = gColors.onError
        )
    } else {
        lightColorScheme(
            primary = gColors.accentCyan,
            onPrimary = gColors.onAccent,
            primaryContainer = gColors.accentContainer,
            onPrimaryContainer = gColors.accentStrong,
            secondary = gColors.verifiedEmerald,
            onSecondary = gColors.onEmerald,
            background = gColors.background,
            onBackground = gColors.textPrimary,
            surface = gColors.surface,
            onSurface = gColors.textPrimary,
            surfaceVariant = gColors.surfaceContainer,
            onSurfaceVariant = gColors.textSecondary,
            surfaceContainerLow = gColors.surface,
            surfaceContainer = gColors.surfaceContainer,
            surfaceContainerHigh = gColors.surfaceHigh,
            outline = gColors.borderStrong,
            outlineVariant = gColors.border,
            error = gColors.errorRed,
            onError = gColors.onError
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? ComponentActivity
            if (activity != null) {
                val barStyle = if (useDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }

                activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = barStyle
                )
            }
        }
    }

    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, languagePreference) {
        createLocalizedContext(baseContext, languagePreference)
    }

    CompositionLocalProvider(
        LocalGrapheneColors provides gColors,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalResources provides localizedContext.resources
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = EtgTypography,
            shapes = EtgShapes,
            content = content
        )
    }
}

fun createLocalizedContext(base: Context, lang: EtgLanguagePreference): Context {
    if (lang == EtgLanguagePreference.SYSTEM) return base
    val locale = when (lang) {
        EtgLanguagePreference.PL -> Locale.forLanguageTag("pl")
        EtgLanguagePreference.EN -> Locale.forLanguageTag("en")
        EtgLanguagePreference.SYSTEM -> Locale.getDefault()
    }
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
}
