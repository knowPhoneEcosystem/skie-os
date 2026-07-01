package com.skiephone.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared top bar for every screen in the app — Skie app icon + screen title,
 * white background with a thin Sky Tint divider beneath it (a quiet nod to
 * the brand book's "header band + accent bar" concept, restrained for daily
 * use rather than a full-bleed colored band on every screen).
 *
 * onBack is optional — omit it for top-level screens (Child, Admin Home tabs)
 * where there's nothing to go back to within the screen stack.
 */
@Composable
fun SkieTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SkieColors.White)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SkieColors.DeepNavy
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else {
                val iconBitmap = remember(context) {
                    try {
                        val iconRes = context.resources.getIdentifier(
                            "ic_launcher", "mipmap", context.packageName
                        )
                        if (iconRes == 0) return@remember null
                        // painterResource() cannot load adaptive icons (layered XML
                        // mipmaps, the standard modern Android icon format) — it only
                        // supports flat VectorDrawables and raster images, and throws
                        // IllegalArgumentException otherwise. ContextCompat.getDrawable()
                        // correctly resolves adaptive icons; converting to a Bitmap here
                        // sidesteps painterResource entirely.
                        val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes)
                            ?: return@remember null
                        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 108
                        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 108
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            width, height, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap.asImageBitmap()
                    } catch (e: Exception) {
                        // Never let icon loading crash the app — worst case, just
                        // show no icon in the top bar.
                        null
                    }
                }
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = "Skiephone",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = SkieColors.DeepNavy,
                modifier = Modifier.weight(1f)
            )
            trailing()
        }
        // Thin accent divider beneath the bar — quiet nod to the brand book's
        // coral bar under the header band, kept subtle for everyday use.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(SkieColors.SkyTint)
        )
    }
}