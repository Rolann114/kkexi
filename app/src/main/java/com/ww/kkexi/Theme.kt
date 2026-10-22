package com.ww.kkexi

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题。
 *
 * ## 两条设计原则
 *
 * **一、四类事件必须一眼可辨。** 这是这个 App 最核心的视觉任务：
 * 打开时间轴，你要能立刻分出「这是上课 / 这是自己安排的事 / 这是死线 / 这是答疑」。
 * 所以 [KindColors] 用四个色相分开，并且在深浅色下重新调过明度，保证两套都有足够对比。
 *
 * **二、界面本身要「退后」。** 日程内容才是主角，界面不该抢戏。
 * 所以背景与卡片之间只拉很浅的层次（`surfaceContainer` 系列的灰度差很小），
 * 圆角统一、边框基本不用，靠留白和层次区分，而不是靠线框。
 *
 * ## 为什么补齐了 surfaceContainer 系列
 * Material 3 从 1.2 起，很多组件（Card / NavigationBar / BottomSheet / Menu）
 * 默认取的是 `surfaceContainer*` 这几档，而不是老的 `surface`+`surfaceVariant`。
 * 不显式给值的话，浅色模式下它们会退化成几乎纯白，卡片与背景分不开、
 * 深色模式下又会糊成一片——这是「界面看起来不精致」的常见根因。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC6FF),
    onPrimary = Color(0xFF00305F),
    primaryContainer = Color(0xFF1D4C7F),
    onPrimaryContainer = Color(0xFFD5E4FF),
    inversePrimary = Color(0xFF1B5FA8),

    secondary = Color(0xFFBCC8DA),
    onSecondary = Color(0xFF26313F),
    secondaryContainer = Color(0xFF33404F),
    onSecondaryContainer = Color(0xFFD8E4F6),

    tertiary = Color(0xFFC7B8E8),
    onTertiary = Color(0xFF31265A),
    tertiaryContainer = Color(0xFF4A3E75),
    onTertiaryContainer = Color(0xFFE9DDFF),

    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE4E7EC),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE4E7EC),
    surfaceVariant = Color(0xFF2A3038),
    onSurfaceVariant = Color(0xFFB8C0CC),
    surfaceTint = Color(0xFF9CC6FF),
    inverseSurface = Color(0xFFE4E7EC),
    inverseOnSurface = Color(0xFF2B3038),

    error = Color(0xFFFFB4A6),
    onError = Color(0xFF5C150C),
    errorContainer = Color(0xFF7A2419),
    onErrorContainer = Color(0xFFFFDAD3),

    outline = Color(0xFF6E7684),
    outlineVariant = Color(0xFF3A424D),
    scrim = Color(0xFF000000),

    // 层次：从「最低」到「最高」，灰度差很小但足以分开卡片与背景。
    surfaceDim = Color(0xFF0A0D11),
    surfaceBright = Color(0xFF3A4048),
    surfaceContainerLowest = Color(0xFF090C10),
    surfaceContainerLow = Color(0xFF14181E),
    surfaceContainer = Color(0xFF181D24),
    surfaceContainerHigh = Color(0xFF21262E),
    surfaceContainerHighest = Color(0xFF2A3038),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5FA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF08213C),
    inversePrimary = Color(0xFF9CC6FF),

    secondary = Color(0xFF4F5A6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E6F0),
    onSecondaryContainer = Color(0xFF1B2430),

    tertiary = Color(0xFF6A5B9E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E1FF),
    onTertiaryContainer = Color(0xFF241A4A),

    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE3E7EE),
    onSurfaceVariant = Color(0xFF444B57),
    surfaceTint = Color(0xFF1B5FA8),
    inverseSurface = Color(0xFF2C3138),
    inverseOnSurface = Color(0xFFEEF1F5),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410E08),

    outline = Color(0xFF767E8B),
    outlineVariant = Color(0xFFC8CFD9),
    scrim = Color(0xFF000000),

    surfaceDim = Color(0xFFDDE1E8),
    surfaceBright = Color(0xFFFBFCFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFF1F4F8),
    surfaceContainerHigh = Color(0xFFEBEFF5),
    surfaceContainerHighest = Color(0xFFE5EAF1),
)

/**
 * 四类事件 + 冲突 + 已取消的标识色。
 *
 * 为什么不用主题色派生：这五个颜色是**语义色**，它们的意义是「互相区分」，
 * 不是「和主色协调」。跟着动态取色走的话，两个类别可能变成相近的色相，
 * 反而破坏了最核心的「一眼可辨」。
 *
 * 两套值分别针对深/浅底调过，不是同一组颜色直接用（浅底上要更深才看得清）。
 */
object KindColors {

    val course = Color(0xFF4C8DF6)
    val activity = Color(0xFF2FA36B)
    val deadline = Color(0xFFE07A1F)
    val officeHour = Color(0xFF8B6FD1)
    val conflict = Color(0xFFE0603A)
    val cancelled = Color(0xFF8A909B)

    fun of(kind: EventKind): Color = when (kind) {
        EventKind.COURSE -> course
        EventKind.ACTIVITY -> activity
        EventKind.DEADLINE -> deadline
        EventKind.OFFICE_HOUR -> officeHour
    }

    /** 深色底上把语义色调亮一点，避免发闷。 */
    fun of(kind: EventKind, dark: Boolean): Color = when (kind) {
        EventKind.COURSE -> if (dark) Color(0xFF6FA8FF) else course
        EventKind.ACTIVITY -> if (dark) Color(0xFF4FC488) else activity
        EventKind.DEADLINE -> if (dark) Color(0xFFF09A4A) else deadline
        EventKind.OFFICE_HOUR -> if (dark) Color(0xFFA98BE8) else officeHour
    }
}

/**
 * 圆角：比 Material 3 默认更圆一点。
 *
 * 日程卡片是密集排列的小方块，圆角大一些看起来更轻、不那么「表格感」。
 */
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * 中文排版。
 *
 * 两条中文特有的调整：
 * 1. **行高比英文更宽**。汉字是满格方块，行距紧会显得拥挤；默认的 M3 行高在中文下偏挤。
 * 2. `LineHeightStyle.Trim` 去掉首末行多余的上下空隙。不做的话，
 *    单行文字在固定高度的容器里会看起来「偏下」，垂直居中怎么调都不对。
 */
private val AppTypography = run {
    val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            fontWeight = weight,
            lineHeightStyle = trim,
        )

    Typography(
        displaySmall = style(30, 38, FontWeight.SemiBold),
        headlineMedium = style(26, 34, FontWeight.SemiBold),
        headlineSmall = style(22, 30, FontWeight.SemiBold),
        titleLarge = style(20, 28, FontWeight.SemiBold),
        titleMedium = style(16, 24, FontWeight.Medium),
        titleSmall = style(14, 21, FontWeight.Medium),
        bodyLarge = style(16, 25),
        bodyMedium = style(14, 22),
        bodySmall = style(12, 19),
        labelLarge = style(14, 20, FontWeight.Medium),
        labelMedium = style(12, 18, FontWeight.Medium),
        labelSmall = style(11, 16, FontWeight.Medium),
    )
}

/**
 * 主题。
 *
 * [dynamicColor] 由设置页控制（默认关闭）。
 * 关闭的原因：这个 App 的语义色（四类事件）需要固定的视觉语言，
 * 跟着壁纸变会让「课程是蓝的、死线是橙的」这个约定不稳。
 * 想和系统更统一的人可以打开——那时主色从壁纸取，语义色仍然固定。
 */
@Composable
fun KkexiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colors = when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
