package home.iptv.tv.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import home.iptv.tv.theme.IptvColors

/** QR code noir sur blanc (marge de 2 modules) en bitmap. */
fun qrBitmap(content: String, sizePx: Int = 600): ImageBitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx) { i ->
        if (matrix[i % sizePx, i / sizePx]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val bmp = android.graphics.Bitmap.createBitmap(pixels, sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PairingScreen(state: StartupUi.Pairing, configPageUrl: String) {
    val qr = remember(state.qrUrl) { qrBitmap(state.qrUrl) }
    Row(
        modifier = Modifier.fillMaxSize().background(IptvColors.Background).padding(horizontal = 96.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(72.dp),
    ) {
        Image(
            bitmap = qr,
            contentDescription = "QR code d'appairage",
            modifier = Modifier.size(380.dp).clip(RoundedCornerShape(20.dp)).background(Color.White),
        )
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Scanne ce code avec ton téléphone", color = IptvColors.Text, fontSize = 34.sp)
            Text(
                state.code.toList().joinToString(" "),
                color = IptvColors.Text, fontSize = 88.sp, letterSpacing = 8.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(configPageUrl, color = IptvColors.TextSecondary, fontSize = 18.sp, modifier = Modifier.width(520.dp))
        }
    }
}
