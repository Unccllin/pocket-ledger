@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.pocketledger.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pocketledger.R

/**
 * Plus Jakarta Sans, bundled (the app never downloads anything). One variable
 * file carries every weight; each `Font` entry pins the axis it needs.
 */
private fun jakarta(weight: FontWeight, axis: Int) = Font(
    resId = R.font.plus_jakarta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
)

val Jakarta = FontFamily(
    jakarta(FontWeight.Normal, 400),
    jakarta(FontWeight.SemiBold, 600),
    jakarta(FontWeight.Bold, 700),
    jakarta(FontWeight.ExtraBold, 800),
)

/**
 * Material slots used by the screens:
 *
 * * `headlineMedium` — screen title (26 / ExtraBold)
 * * `displayMedium` — hero amount (40 / ExtraBold)
 * * `titleMedium` — row title (15 / SemiBold)
 * * `labelLarge` — row amount (15 / Bold)
 * * `bodyMedium` — secondary (13 / Regular)
 * * `labelMedium` — secondary strong (13 / SemiBold)
 * * `labelSmall` — section label (12 / Bold, tracked)
 * * `bodySmall` — caption (12 / Regular)
 *
 * Sizes are `sp`, so the whole screen grows with the system font scale.
 */
val LedgerTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Jakarta,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
