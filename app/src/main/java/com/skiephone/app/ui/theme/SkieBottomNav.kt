package com.skiephone.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SkieNavTab(val label: String) {
    HOME("Home"),
    APPS("Apps"),
    COMPLIANCE("Compliance"),
    SETTINGS("Settings")
}

/**
 * Persistent bottom navigation for the authenticated admin area — turns the
 * previous "tap a card, navigate away, tap back" pattern into a real tabbed
 * app structure, matching standard iOS/Android tab-bar conventions. Shown
 * only within the admin portal; never on the child screen or wizard flow.
 */
@Composable
fun SkieBottomNav(
    current: SkieNavTab,
    onSelect: (SkieNavTab) -> Unit
) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SkieColors.Line))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SkieColors.White)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SkieNavTab.entries.forEach { tab ->
                val selected = tab == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (selected) SkieColors.SkyBlue else Color.Transparent, CircleShape)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) SkieColors.SkyBlue else SkieColors.Grey
                    )
                }
            }
        }
    }
}