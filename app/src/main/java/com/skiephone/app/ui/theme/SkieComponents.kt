package com.skiephone.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard grouped card — the base visual unit across the app, matching the
 * Screen-Time-style grouped-list aesthetic. White surface, soft rounded
 * corners, no harsh borders (a subtle Line-colored 1px stroke instead).
 */
@Composable
fun SkieCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = SkieColors.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SkieRadius.card))
            .background(backgroundColor)
            .padding(SkieSpacing.md)
    ) {
        content()
    }
}

/** Section header — small caps-style label above a group of cards, iOS Settings style. */
@Composable
fun SkieSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = SkieColors.Grey,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp)
    )
}

/**
 * Segmented step-progress bar for the wizard and compliance flows — Apple
 * Setup Assistant style. Each segment fills solid Sky Blue when complete,
 * a lighter Sky Tint when upcoming, with a small gap between segments.
 */
@Composable
fun SkieStepProgress(totalSteps: Int, currentStep: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 1..totalSteps) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i <= currentStep) SkieColors.SkyBlue else SkieColors.SkyTint)
            )
        }
    }
}

/** Small rounded badge — used for status tags like "Required", "Locked", "Active". */
@Composable
fun SkieBadge(text: String, color: Color, backgroundColor: Color) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(SkieRadius.chip))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}