package app.huangmei.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF1B1210)
    val Card = Color(0xFF33231D)
    val Gold = Color(0xFFF2C14E)
    val OnGold = Color(0xFF2B1A00)
    val Red = Color(0xFFB71C1C)
    val DeepRed = Color(0xFF4A0E0E)
    val Text = Color(0xFFFFF6E5)
    val Dim = Color(0xFFBFAF9F)
}

@Composable
fun HuangmeiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Gold,
            onPrimary = Palette.OnGold,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Card,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    fontSize: TextUnit = 26.sp,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Palette.Gold else Palette.Card,
            contentColor = if (primary) Palette.OnGold else Palette.Text,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

/** 按住不放 [holdMs] 毫秒才触发；给家人进维护面板用，奶奶碰一下没反应。 */
@Composable
fun HoldToOpen(
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Long = 4000,
    content: @Composable BoxScope.() -> Unit,
) {
    val trigger by rememberUpdatedState(onTrigger)
    Box(
        modifier.pointerInput(holdMs) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var timedOut = true
                withTimeoutOrNull(holdMs) {
                    waitForUpOrCancellation()
                    timedOut = false
                }
                if (timedOut) trigger()
            }
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    if (totalSec < 60) return "${totalSec}秒"
    val totalMin = totalSec / 60
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}小时${m}分" else "${m}分钟"
}
