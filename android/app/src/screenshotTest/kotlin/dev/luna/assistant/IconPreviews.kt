package dev.luna.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

/** The launcher icon as phones mask it: circle, rounded square, squircle-ish. The real icon resources, cropped like a launcher does. */
@Composable
private fun Mask(shape: androidx.compose.ui.graphics.Shape) {
    Box(Modifier.size(96.dp).clip(shape)) {
        // the adaptive icon is 108dp with the outer 18dp hidden by the mask, so draw it larger and crop
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(96.dp))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(96.dp))
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 140)
@Composable
fun LauncherIcon() {
    Box(Modifier.background(Color(0xFFE9E7EF)).padding(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Mask(CircleShape)
            Mask(RoundedCornerShape(28.dp))
            Mask(RoundedCornerShape(40.dp))
        }
    }
}
