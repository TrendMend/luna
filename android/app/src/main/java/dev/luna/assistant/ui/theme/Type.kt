package dev.luna.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bigger than Material's defaults, because Luna is used by an older reader: body text is 20sp, nothing is lighter than Normal,
// and every size is in sp so the system font-size setting scales all of it.
private val family = FontFamily.Default

val Typography = Typography(
    displaySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    titleSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)
