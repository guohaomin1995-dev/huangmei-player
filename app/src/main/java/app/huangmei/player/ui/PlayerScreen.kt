package app.huangmei.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.huangmei.player.AppViewModel
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(vm: AppViewModel) {
    BackHandler { vm.goHome() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VlcVideo(vm, Modifier.fillMaxSize())

            if (vm.paused) {
                BigButton(
                    "继续播放",
                    onClick = { vm.togglePause() },
                    primary = true,
                    icon = Icons.Filled.PlayArrow,
                    fontSize = 46.sp,
                    modifier = Modifier.align(Alignment.Center).heightIn(min = 120.dp),
                )
            }

            vm.message?.let {
                Text(
                    it,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(20.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    fontSize = 30.sp,
                    color = Color.White,
                )
            }

            if (vm.volumeShown) {
                VolumeBar(vm.volumeLevel, Modifier.align(Alignment.CenterEnd).padding(24.dp))
            }
        }

        InfoBar(vm)

        Row(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .background(Palette.Bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ControlButton(Icons.Filled.GridView, "回海报", onClick = { vm.goHome() })
            ControlButton(Icons.Filled.SkipPrevious, "上一部", onClick = { vm.skip(-1) })
            ControlButton(
                if (vm.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (vm.paused) "播放" else "暂停",
                onClick = { vm.togglePause() },
                primary = true,
            )
            ControlButton(Icons.Filled.SkipNext, "下一部", onClick = { vm.skip(1) })
            ControlButton(Icons.AutoMirrored.Filled.VolumeDown, "小声", onClick = { vm.changeVolume(false) })
            ControlButton(Icons.AutoMirrored.Filled.VolumeUp, "大声", onClick = { vm.changeVolume(true) })
        }
    }
}

@Composable
private fun InfoBar(vm: AppViewModel) {
    val dur = vm.durationMs
    val pos = vm.positionMs
    Column(Modifier.fillMaxWidth().background(Palette.Bg)) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.Card)) {
            if (dur > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Palette.Gold),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "正在放：${vm.current?.title.orEmpty()}",
                modifier = Modifier.weight(1f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Gold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (dur > 0) {
                Text("已放 ${formatDuration(pos)}，共 ${formatDuration(dur)}", fontSize = 22.sp, color = Palette.Text)
            }
        }
    }
}

@Composable
private fun RowScope.ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Palette.Gold else Palette.Card)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = if (primary) Palette.OnGold else Palette.Gold,
        )
        Text(
            label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (primary) Palette.OnGold else Palette.Text,
        )
    }
}

@Composable
private fun VolumeBar(level: Float, modifier: Modifier = Modifier) {
    val filled = (level * 10).roundToInt()
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("声音", fontSize = 24.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            repeat(10) { i ->
                Box(
                    Modifier
                        .size(width = 14.dp, height = (16 + i * 3).dp)
                        .background(if (i < filled) Palette.Gold else Palette.Dim.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

/** libVLC 的画面：surface 建好就挂给 VLC；离开播放界面或 App 退到后台时 surface 销毁，自动摘下。 */
@Composable
private fun VlcVideo(vm: AppViewModel, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        vm.onSurfaceReady(this@apply, width, height)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = vm.onSurfaceGone()
                })
            }
        },
        update = { it.keepScreenOn = vm.isPlaying },
        onRelease = { vm.onSurfaceGone() },
        modifier = modifier,
    )
}
