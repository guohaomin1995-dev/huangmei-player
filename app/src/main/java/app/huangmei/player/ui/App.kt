package app.huangmei.player.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.huangmei.player.AppViewModel
import app.huangmei.player.Screen
import app.huangmei.player.Storage
import app.huangmei.player.VideoLibrary

@Composable
fun App(vm: AppViewModel) {
    HuangmeiTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.Bg)
                .windowInsetsPadding(WindowInsets.displayCutout),
        ) {
            when {
                !vm.hasStorageAccess -> PermissionScreen()
                vm.screen == Screen.Player -> PlayerScreen(vm)
                else -> HomeScreen(vm)
            }
            if (vm.silentTest) {
                // 提醒：别把静音测试状态留给奶奶
                Text(
                    "静音测试中",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(Palette.Red, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    fontSize = 16.sp,
                    color = Palette.Text,
                )
            }
            if (vm.showAdmin) AdminPanel(vm)
        }
    }
}

@Composable
private fun PermissionScreen() {
    val activity = LocalActivity.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("第一次使用需要设置一下", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Palette.Gold)
            Text(
                "请允许「黄梅戏」读取视频文件（这一步由家人来做）\n视频放在：内部存储 / Download / ${VideoLibrary.FOLDER}",
                fontSize = 22.sp,
                color = Palette.Text,
                textAlign = TextAlign.Center,
            )
            BigButton("去允许", onClick = { activity?.let(Storage::request) }, primary = true)
        }
    }
}
