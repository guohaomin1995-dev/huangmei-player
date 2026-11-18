package app.huangmei.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.huangmei.player.AppViewModel
import app.huangmei.player.COLS
import app.huangmei.player.PosterLoader
import app.huangmei.player.Paging
import app.huangmei.player.ROWS
import app.huangmei.player.Video
import app.huangmei.player.VideoLibrary
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: AppViewModel) {
    // 奶奶按返回键不会退出；挑选常看时返回 = 完成
    BackHandler { if (vm.pickingFavorites) vm.finishPickingFavorites() }

    val videos = vm.videos
    if (videos.isEmpty()) {
        EmptyHome(vm)
        return
    }

    val layout = vm.layout
    val pageCount = maxOf(1, layout.pages.size)
    val page = vm.homePage.coerceIn(0, pageCount - 1)
    val items = layout.pages.getOrElse(page) { emptyList() }
    val lastPath = remember(vm.progressTick) { vm.prefs.lastPath }
    val picking = vm.pickingFavorites

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (picking) PickBanner(vm)
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.width(112.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PageButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        label = "上一页",
                        enabled = page > 0,
                        onClick = { vm.homePage = page - 1 },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    // 翻得很远时一下回到第一页（常看）
                    PageButton(
                        icon = Icons.Filled.FirstPage,
                        label = "回第一页",
                        enabled = page > 0,
                        onClick = { vm.homePage = 0 },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        labelSize = 22.sp,
                    )
                }

                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(ROWS) { r ->
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            repeat(COLS) { c ->
                                val cell = Modifier.weight(1f).fillMaxHeight()
                                val v = items.getOrNull(r * COLS + c)
                                if (v != null) {
                                    // 按戏区分格子状态，翻页后不会带着上一部的海报
                                    key(v.path) {
                                        val picked = vm.favorites.indexOf(v.path).takeIf { it >= 0 }?.plus(1)
                                        PosterCard(
                                            video = v,
                                            posters = vm.posters,
                                            isLast = !picking && v.path == lastPath,
                                            progress = remember(vm.progressTick, v.path) { vm.progressOf(v) },
                                            pickedNumber = if (picking) picked else null,
                                            onClick = { if (picking) vm.toggleFavorite(v) else vm.play(v) },
                                            modifier = cell,
                                        )
                                    }
                                } else {
                                    Spacer(cell)
                                }
                            }
                        }
                    }
                }

                Column(
                    Modifier.width(112.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PageButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = "下一页",
                        enabled = page < pageCount - 1,
                        onClick = { vm.homePage = page + 1 },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    // 按住页码 4 秒 → 维护面板
                    HoldToOpen(
                        onTrigger = { vm.showAdmin = true },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        val label = if (page < layout.favoritePages) "常看" else "${page + 1} / $pageCount"
                        Text(label, fontSize = 22.sp, color = Palette.Dim)
                    }
                }
            }
        }

        vm.message?.let {
            Text(
                it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 26.sp,
                color = Color.White,
            )
        }

        vm.resumeOffer?.let { ResumeOffer(it, vm) }
    }
}

/** 挑选常看时顶部的提示条。 */
@Composable
private fun PickBanner(vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Red)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "挑选常看：点海报加入或取消（已选 ${vm.favorites.size} / ${Paging.MAX_FAVORITES}），按挑的顺序排在第一页",
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            color = Color.White,
        )
        BigButton("完成", onClick = { vm.finishPickingFavorites() }, primary = true, fontSize = 24.sp)
    }
}

@Composable
private fun EmptyHome(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!vm.scanned) {
                Text("正在找视频…", fontSize = 32.sp, color = Palette.Text)
            } else {
                Text("还没有找到黄梅戏视频", fontSize = 36.sp, color = Palette.Gold, fontWeight = FontWeight.Bold)
                Text(
                    "请把视频放到：内部存储 / Download / ${VideoLibrary.FOLDER}\n（或 Movies / ${VideoLibrary.FOLDER}）",
                    fontSize = 24.sp,
                    color = Palette.Text,
                    textAlign = TextAlign.Center,
                )
                BigButton("重新找一遍", onClick = { vm.refresh() })
            }
        }
        HoldToOpen(
            onTrigger = { vm.showAdmin = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(120.dp, 56.dp),
        ) {
            Text("设置", fontSize = 18.sp, color = Palette.Dim)
        }
    }
}

@Composable
private fun PageButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelSize: TextUnit = 24.sp,
) {
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.2f)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Card)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(72.dp), tint = Palette.Gold)
        Text(label, fontSize = labelSize, fontWeight = FontWeight.Bold, color = Palette.Text)
    }
}

@Composable
private fun PosterCard(
    video: Video,
    posters: PosterLoader,
    isLast: Boolean,
    progress: Float,
    pickedNumber: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.Card)
            .then(if (isLast || pickedNumber != null) Modifier.border(4.dp, Palette.Gold, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Poster(video, posters, Modifier.fillMaxSize())
            if (pickedNumber != null) {
                Text(
                    "常看 $pickedNumber",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Palette.Gold, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.OnGold,
                )
            }
            if (isLast) {
                Text(
                    "上次在看",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Palette.Red, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            if (progress > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Palette.Gold))
                }
            }
        }
        Text(
            video.title,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.Text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 没有副标题也占一行，同一排海报的图片才一样高
        Text(
            video.subtitle,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            fontSize = 18.sp,
            color = Palette.Dim,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Poster(video: Video, posters: PosterLoader, modifier: Modifier = Modifier) {
    // 换页时同一个格子会换成别的戏：必须按新戏重新取图，不能沿用上一张
    val image by produceState(initialValue = posters.cached(video), video.posterKey) {
        value = posters.cached(video) ?: posters.load(video)
    }
    val img = image
    if (img != null) {
        Image(img, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.background(Brush.verticalGradient(fallbackColors(video.title))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                video.title,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Gold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** 打开时：倒计时后自动接着放上次那部。 */
@Composable
private fun ResumeOffer(video: Video, vm: AppViewModel) {
    var seconds by remember(video.path) { mutableIntStateOf(8) }
    LaunchedEffect(video.path) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
        vm.acceptResume()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "接着放《${video.title}》",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Gold,
            )
            Text("$seconds 秒后自动开始", fontSize = 26.sp, color = Palette.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigButton(
                    "现在就放",
                    onClick = { vm.acceptResume() },
                    primary = true,
                    icon = Icons.Filled.PlayArrow,
                    fontSize = 30.sp,
                )
                BigButton("我自己选", onClick = { vm.dismissResume() }, fontSize = 30.sp)
            }
        }
    }
}

/** 没有海报图的戏用纯色卡片：按戏名固定配色，同一出戏颜色一样，不同戏好区分。 */
private val fallbackPalette = listOf(
    Color(0xFFB0302A) to Color(0xFF4A0E0E), // 朱红
    Color(0xFF1F4E79) to Color(0xFF0B1E33), // 靛青
    Color(0xFF2E6B45) to Color(0xFF0E2617), // 墨绿
    Color(0xFF6B2D6B) to Color(0xFF2A0F2A), // 绛紫
    Color(0xFF8C5A2B) to Color(0xFF35200C), // 赭石
    Color(0xFF2B3A8C) to Color(0xFF0F1433), // 藏蓝
    Color(0xFF9D2B5A) to Color(0xFF3A0E20), // 胭脂
    Color(0xFF1F7A72) to Color(0xFF0A2E2B), // 松绿
)

private fun fallbackColors(title: String): List<Color> =
    fallbackPalette[Math.floorMod(title.hashCode(), fallbackPalette.size)].toList()
