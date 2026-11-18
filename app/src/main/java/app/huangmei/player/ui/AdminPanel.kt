package app.huangmei.player.ui

import android.app.ActivityManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.huangmei.player.AppViewModel
import app.huangmei.player.CallGuard
import app.huangmei.player.Kiosk
import app.huangmei.player.MainActivity
import app.huangmei.player.Storage
import app.huangmei.player.VideoLibrary

/** 家人用的维护面板：首页按住右下角页码 4 秒打开。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminPanel(vm: AppViewModel) {
    val activity = LocalActivity.current ?: return
    BackHandler { vm.showAdmin = false }

    // 每次操作后加一，重新读取锁定状态
    var tick by remember { mutableIntStateOf(0) }
    val owner = remember(tick) { Kiosk.isDeviceOwner(activity) }
    val lockState = remember(tick) { Kiosk.lockState(activity) }
    val home = remember(tick) { Kiosk.isHomeEnabled(activity) }
    var confirmRelease by remember { mutableStateOf(false) }

    fun act(block: () -> Unit) {
        block()
        tick++
    }

    // 亮度：自动（交给系统，跟着屋里光线变）或手动 10%~100%（调本窗口亮度）
    val autoBrightness = remember(tick) { vm.prefs.autoBrightness }
    val manualBrightness = remember(tick) { vm.prefs.manualBrightness }
    fun setBrightness(auto: Boolean, manual: Float = manualBrightness) = act {
        vm.prefs.autoBrightness = auto
        vm.prefs.manualBrightness = ((manual * 10).roundToInt() / 10f).coerceIn(0.1f, 1f)
        (activity as? MainActivity)?.applyBrightness()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Bg)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("维护面板（家人用）", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Gold)

            InfoLine("视频目录", VideoLibrary.rootDirs().joinToString("\n") { it.path })
            InfoLine("视频数量", "${vm.videos.size} 部")
            InfoLine("文件权限", if (vm.hasStorageAccess) "已允许" else "未允许")
            InfoLine("设备所有者", if (owner) "是（可以完全锁定）" else "否（只能用屏幕固定）")
            InfoLine(
                "锁定状态",
                when (lockState) {
                    ActivityManager.LOCK_TASK_MODE_LOCKED -> "已锁定"
                    ActivityManager.LOCK_TASK_MODE_PINNED -> "屏幕已固定"
                    else -> "未锁定"
                },
            )
            if (!owner) InfoLine("当桌面", if (home) "是" else "否")

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdminButton("重新扫描视频") { vm.refresh() }
                AdminButton("挑选常看（已选 ${vm.favorites.size} 部）") { vm.startPickingFavorites() }
                AdminButton(if (vm.autoResume) "打开时自动续播：开" else "打开时自动续播：关") { vm.toggleAutoResume() }
                AdminButton(if (vm.silentTest) "静音测试：开" else "静音测试：关") { vm.toggleSilentTest() }
                if (!vm.hasStorageAccess) AdminButton("去允许文件权限") { Storage.request(activity) }
            }

            InfoLine(
                "亮度",
                if (autoBrightness) "自动（跟着屋里光线变）" else "手动 ${(manualBrightness * 100).roundToInt()}%",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdminButton(if (autoBrightness) "自动亮度：开" else "自动亮度：关") { setBrightness(!autoBrightness) }
                // 按暗/亮就切到手动
                AdminButton("暗一点") { setBrightness(false, manualBrightness - 0.1f) }
                AdminButton("亮一点") { setBrightness(false, manualBrightness + 0.1f) }
            }

            val serviceOn = remember(tick) { CallGuard.isServiceOn(activity) }
            val listenerOn = remember(tick) { CallGuard.isListenerOn(activity) }
            InfoLine(
                "微信自动接听",
                if (!vm.autoAnswer) {
                    "关"
                } else {
                    buildString {
                        append(if (serviceOn) "开" else "开，但辅助服务没打开（接不了）")
                        if (!listenerOn) append("；通知监听没打开")
                        val only = vm.prefs.answerOnly
                        append(if (only.isEmpty()) "；好友打来都接" else "；只接 ${only.joinToString("、")}")
                        append("；响 ${vm.prefs.answerDelayMs / 1000} 秒后接")
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdminButton(if (vm.autoAnswer) "微信自动接听：开" else "微信自动接听：关") { act { vm.toggleAutoAnswer() } }
                if (vm.autoAnswer && !serviceOn) {
                    AdminButton("去打开辅助服务") {
                        act {
                            Kiosk.unlock(activity, vm.prefs)
                            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (lockState == ActivityManager.LOCK_TASK_MODE_NONE) {
                    AdminButton(if (owner) "开启锁定" else "固定屏幕（测试）") { act { Kiosk.lock(activity, vm.prefs) } }
                } else {
                    AdminButton("退出锁定") { act { Kiosk.unlock(activity, vm.prefs) } }
                }
                if (!owner) {
                    AdminButton(if (home) "取消当桌面" else "设为桌面") { act { Kiosk.toggleHome(activity) } }
                }
                AdminButton("打开系统设置") {
                    act {
                        Kiosk.unlock(activity, vm.prefs)
                        activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }

            if (owner) {
                AdminButton(
                    if (confirmRelease) "再点一次：确认解除设备所有者" else "解除设备所有者（卸载前用）",
                    danger = true,
                ) {
                    if (confirmRelease) {
                        act { Kiosk.releaseOwner(activity, vm.prefs) }
                        confirmRelease = false
                    } else {
                        confirmRelease = true
                    }
                }
            }

            BigButton("关闭", onClick = { vm.showAdmin = false }, primary = true)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.width(140.dp), fontSize = 20.sp, color = Palette.Dim)
        Text(value, fontSize = 20.sp, color = Palette.Text)
    }
}

@Composable
private fun AdminButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Palette.Red else Palette.Card,
            contentColor = Palette.Text,
        ),
    ) {
        Text(text, fontSize = 20.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
}
