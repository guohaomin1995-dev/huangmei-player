package app.huangmei.player

import android.app.Application
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import android.view.KeyEvent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import java.util.Locale
import org.videolan.libvlc.MediaPlayer as VlcPlayer

enum class Screen { Home, Player }

const val COLS = 3
const val ROWS = 2
const val PER_PAGE = COLS * ROWS

/**
 * 播放用 libVLC：RMVB（RealVideo/Cook）、FLV、MP4 都能放。
 * 播放列表、断点续播、自动下一部都在这里自己管。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val posters = PosterLoader(app)
    private val audio = app.getSystemService(AudioManager::class.java)

    // ---- 片库与首页 ----
    var videos by mutableStateOf<List<Video>>(emptyList()); private set
    var scanned by mutableStateOf(false); private set
    var hasStorageAccess by mutableStateOf(Storage.hasAccess(app)); private set
    var screen by mutableStateOf(Screen.Home); private set
    var homePage by mutableIntStateOf(0)

    /** 家人挑的"常看"（按挑选顺序），排在海报墙最前面。 */
    var favorites by mutableStateOf(prefs.favorites); private set

    /** 维护面板里点了"挑选常看"：点海报是加入/取消常看，不是播放。 */
    var pickingFavorites by mutableStateOf(false); private set

    /** 海报墙的分页：平时常看在前；挑选时按原顺序全部列出。播放顺序也按它。 */
    val layout by derivedStateOf {
        if (pickingFavorites) Paging.plain(videos) else Paging.layout(videos, favorites)
    }
    var showAdmin by mutableStateOf(false)
    var autoResume by mutableStateOf(prefs.autoResume); private set
    var silentTest by mutableStateOf(prefs.silentTest); private set
    var resumeOffer by mutableStateOf<Video?>(null); private set

    /** 首页的"上次在看"和进度条从 prefs 读，变了就加一让界面重读。 */
    var progressTick by mutableIntStateOf(0); private set

    // ---- 播放 ----
    var current by mutableStateOf<Video?>(null); private set
    /** 用户（或 App 退到后台）让它停着：显示"继续播放"大按钮。 */
    var paused by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var message by mutableStateOf<String?>(null); private set
    var volumeShown by mutableStateOf(false); private set
    var volumeLevel by mutableFloatStateOf(0f); private set

    private var playlist: List<Video> = emptyList()
    private var index = -1
    private var visible = false
    private var offeredResume = false
    private var errorStreak = 0
    private var messageJob: Job? = null
    /** 放不了时 2.5 秒后自动换下一部；回海报/手动换片/离开 App 时必须取消，否则会在后台一直跳。 */
    private var errorSkipJob: Job? = null
    private var volumeJob: Job? = null

    /** 画面挂上之前不能开始放（libVLC 没有画面窗口会只出声音），先记着等挂上再放。 */
    private var videoAttached = false
    private var pendingStartMs: Long? = null

    /** 暂停期间画面被摘下过（App 退到后台）：继续时从当前位置重新打开，否则会花屏几秒。 */
    private var surfaceLostWhilePaused = false

    /** 换片后，旧片子排队中的进度事件要丢掉，等新片子 Opening 了才收。 */
    private var acceptTime = false

    private val libVlc = LibVLC(
        app,
        arrayListOf(
            // 各部戏录音音量不一，压一压特别响的
            "--audio-filter=normvol",
        ),
    )
    private val player = VlcPlayer(libVlc)

    // 蓝牙遥控器 / 耳机按键（AVRCP）走系统媒体会话
    private val session = MediaSession(app, "huangmei").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = remotePlay()
            override fun onPause() {
                if (!paused) togglePause()
            }
            override fun onSkipToNext() = skip(1)
            override fun onSkipToPrevious() = skip(-1)
            override fun onStop() {
                if (!paused) togglePause()
            }
        })
        isActive = true
    }

    init {
        player.setEventListener { e -> onPlayerEvent(e) }
        updateSession()

        viewModelScope.launch {
            var n = 0
            while (true) {
                delay(1000)
                if (!isPlaying) continue
                n++
                if (n % 10 == 0) saveProgress()
                if (silentTest && n % 5 == 0) logStats()
            }
        }
    }

    private fun onPlayerEvent(e: VlcPlayer.Event) {
        when (e.type) {
            VlcPlayer.Event.Opening -> acceptTime = true

            VlcPlayer.Event.Playing -> {
                acceptTime = true
                isPlaying = true
                errorStreak = 0
                // 真正放起来了才记为"上次在看"，放不了的片子不会被自动续播
                current?.let { prefs.lastPath = it.path }
                if (silentTest) player.setVolume(0)
                // 应用不在前台时别偷偷出声（手机测试时蓝牙耳机可能触发）
                if (!visible) pauseInternal()
                updateSession()
            }

            VlcPlayer.Event.Paused, VlcPlayer.Event.Stopped -> {
                isPlaying = false
                updateSession()
            }

            VlcPlayer.Event.TimeChanged -> if (acceptTime) positionMs = e.timeChanged

            VlcPlayer.Event.LengthChanged -> if (acceptTime && e.lengthChanged > 0) {
                durationMs = e.lengthChanged
                current?.let { prefs.saveDuration(it.path, e.lengthChanged) }
            }

            VlcPlayer.Event.EndReached -> {
                isPlaying = false
                // 这部放完了：进度清零，接着放下一部（下一部以前看过一半会接着放）
                current?.let { prefs.savePosition(it.path, 0L) }
                if (screen == Screen.Player && visible) moveTo(index + 1, saveCurrent = false)
            }

            VlcPlayer.Event.EncounteredError -> {
                Log.w(TAG, "播放出错 ${current?.file?.name}")
                isPlaying = false
                onError()
            }
        }
    }

    private fun onError() {
        errorStreak++
        if (errorStreak >= playlist.size) {
            showMessage("视频都放不了，请打电话给家人", 15_000)
            return
        }
        showMessage("这部戏放不了，换下一部")
        errorSkipJob?.cancel()
        errorSkipJob = viewModelScope.launch {
            delay(2500)
            if (screen == Screen.Player && visible) skip(1)
        }
    }

    // ---- 生命周期 ----

    fun onVisible(isVisible: Boolean) {
        visible = isVisible
        if (!isVisible) {
            errorSkipJob?.cancel()
            if (!paused && current != null) {
                pauseInternal()
                pausedByHide = true
            }
        } else {
            // 微信视频挂断后切回来：原来在放就接着放
            val fromCall = CallGuard.consumeReturnFromCall()
            if (pausedByHide && fromCall && screen == Screen.Player && paused) {
                Log.i(TAG, "通话结束回来，接着放")
                togglePause()
            }
            pausedByHide = false
        }
    }

    /** 播放中因为界面被挡住（比如微信来电）而自动暂停的。 */
    private var pausedByHide = false

    var autoAnswer by mutableStateOf(prefs.autoAnswer); private set

    fun toggleAutoAnswer() {
        autoAnswer = !autoAnswer
        prefs.autoAnswer = autoAnswer
        Kiosk.refreshLockTaskPackages(getApplication(), prefs)
        if (autoAnswer) CallGuard.ensureServicesEnabled(getApplication(), prefs)
    }

    /**
     * 播放界面的画面：SurfaceView 的 surface 建好（或尺寸变了）时挂给 VLC，surface 销毁（离开播放界面、
     * App 退到后台）时摘下。VLC 要等画面挂上才真正开始放，所以没挂上时先把"要放"记在 pendingStartMs。
     */
    fun onSurfaceReady(view: SurfaceView, width: Int, height: Int) {
        val vout = player.vlcVout
        if (!videoAttached) {
            Log.i(TAG, "surface ready ${width}x$height pending=$pendingStartMs")
            vout.setVideoView(view)
            vout.setWindowSize(width, height)
            vout.attachViews()
            videoAttached = true
            pendingStartMs?.let { start ->
                pendingStartMs = null
                startNow(start)
            }
        } else {
            vout.setWindowSize(width, height)
        }
    }

    fun onSurfaceGone() {
        if (!videoAttached) return
        Log.i(TAG, "surface gone")
        player.vlcVout.detachViews()
        videoAttached = false
        if (current != null) surfaceLostWhilePaused = true
    }

    fun refresh() {
        hasStorageAccess = Storage.hasAccess(getApplication())
        if (!hasStorageAccess) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { VideoLibrary.scan() }
            videos = list
            scanned = true
            progressTick++
            if (!offeredResume) {
                offeredResume = true
                val last = list.firstOrNull { it.path == prefs.lastPath }
                if (last != null) {
                    layout.pageOf(last.path)?.let { homePage = it }
                    if (autoResume && screen == Screen.Home) resumeOffer = last
                }
            }
        }
    }

    // ---- 播放控制 ----

    fun play(video: Video) {
        resumeOffer = null
        // 播放顺序和海报墙一致：常看在前，其余在后
        val list = Paging.layout(videos, favorites).ordered
        val i = list.indexOfFirst { it.path == video.path }
        if (i < 0) return
        playlist = list
        errorStreak = 0
        moveTo(i, saveCurrent = false)
        screen = Screen.Player
        ensureMinVolume()
    }

    /** 换到播放列表第 i 部（首尾相接），以前看过一半就接着放。 */
    private fun moveTo(i: Int, saveCurrent: Boolean) {
        val n = playlist.size
        if (n == 0) return
        errorSkipJob?.cancel()
        if (saveCurrent) saveProgress()
        index = Math.floorMod(i, n)
        val video = playlist[index]
        current = video
        val start = resumePosition(video)
        positionMs = start
        durationMs = prefs.duration(video.path)
        paused = false
        if (videoAttached) startNow(start) else pendingStartMs = start
        if (start > 0) showMessage("接着上次的地方放")
    }

    private fun startNow(startMs: Long) {
        val video = current ?: return
        Log.i(TAG, "startNow ${video.file.name} at $startMs silent=$silentTest")
        acceptTime = false
        surfaceLostWhilePaused = false
        val media = Media(libVlc, Uri.fromFile(video.file)).apply {
            setHWDecoderEnabled(true, false)
            if (startMs > 0) addOption(String.format(Locale.US, ":start-time=%.1f", startMs / 1000.0))
            // 静音测试：连音轨都不解码，绝对不出声（调试开关 silentDecodeAudio 打开时照样解码，但 VLC 音量为 0）
            if (silentTest && !prefs.silentDecodeAudio) addOption(":no-audio")
        }
        player.setMedia(media)
        media.release()
        player.setVolume(if (silentTest) 0 else 100)
        player.play()
    }

    private fun pauseInternal() {
        paused = true
        saveProgress()
        player.pause()
    }

    fun togglePause() {
        if (current == null) return
        if (!paused) {
            pauseInternal()
        } else {
            paused = false
            ensureMinVolume()
            val state = player.playerState
            val canUnpause = state == IMedia.State.Paused || state == IMedia.State.Playing
            if (canUnpause && !surfaceLostWhilePaused) {
                player.play()
                return
            }
            // 停掉了，或暂停期间画面被摘下过：往回 2 秒重新打开，从关键帧开始解码，画面干净
            val start = if (canUnpause) (positionMs - 2_000).coerceAtLeast(0) else resumePosition(current!!)
            if (videoAttached) startNow(start) else pendingStartMs = start
        }
    }

    fun skip(delta: Int) {
        if (playlist.isEmpty()) return
        moveTo(index + delta, saveCurrent = true)
        if (screen != Screen.Player) screen = Screen.Player
    }

    /** 遥控器"播放"键：在播放界面就继续，在海报页就放上次那部。 */
    private fun remotePlay() {
        if (screen == Screen.Player) {
            if (paused) togglePause()
            return
        }
        (resumeOffer ?: videos.firstOrNull { it.path == prefs.lastPath } ?: videos.firstOrNull())?.let { play(it) }
    }

    fun goHome() {
        errorSkipJob?.cancel()
        saveProgress()
        pendingStartMs = null
        paused = true
        player.stop()
        screen = Screen.Home
        current?.let { cur -> layout.pageOf(cur.path)?.let { homePage = it } }
        progressTick++
    }

    // ---- 常看 ----

    fun startPickingFavorites() {
        showAdmin = false
        pickingFavorites = true
        homePage = 0
    }

    fun finishPickingFavorites() {
        pickingFavorites = false
        homePage = 0
    }

    /** 挑选时点海报：加入或取消常看（最多 Paging.MAX_FAVORITES 部）。 */
    fun toggleFavorite(video: Video) {
        val now = favorites.toMutableList()
        if (!now.remove(video.path)) {
            if (now.size >= Paging.MAX_FAVORITES) {
                showMessage("常看最多 ${Paging.MAX_FAVORITES} 部，先取消一部")
                return
            }
            now.add(video.path)
        }
        favorites = now
        prefs.favorites = now
    }

    fun acceptResume() {
        resumeOffer?.let { play(it) }
    }

    fun dismissResume() {
        resumeOffer = null
    }

    fun toggleAutoResume() {
        autoResume = !autoResume
        prefs.autoResume = autoResume
    }

    fun toggleSilentTest() {
        silentTest = !silentTest
        prefs.silentTest = silentTest
        player.setVolume(if (silentTest) 0 else 100)
    }

    fun changeVolume(up: Boolean) {
        // 静音测试时只显示音量条，不动系统音量
        if (!silentTest) runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
            // "小声"按到底也留一点声音，不然会以为坏了
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val floor = maxOf(1, max / 10)
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) < floor) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
            }
        }
        showVolume()
    }

    private fun ensureMinVolume() {
        if (silentTest) return
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val min = (max * prefs.minVolumePercent + 99) / 100
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) < min) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0)
            }
        }
    }

    private fun showVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        volumeShown = true
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            delay(2000)
            volumeShown = false
        }
    }

    private fun showMessage(text: String, ms: Long = 3000) {
        message = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(ms)
            message = null
        }
    }

    // ---- 遥控器 / 键盘 ----

    fun isRemoteKey(code: Int) = code in remoteKeys

    fun onRemoteKey(code: Int) {
        if (showAdmin || !hasStorageAccess) return
        when (screen) {
            Screen.Player -> when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> skip(1)
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> skip(-1)
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) togglePause()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) togglePause()
                else -> togglePause()
            }

            Screen.Home -> {
                val pages = maxOf(1, layout.pages.size)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT ->
                        homePage = (homePage + 1).coerceAtMost(pages - 1)

                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                        homePage = (homePage - 1).coerceAtLeast(0)

                    KeyEvent.KEYCODE_MEDIA_PAUSE -> Unit
                    else -> remotePlay()
                }
            }
        }
    }

    // ---- 进度 ----

    /** 看了不到 30 秒、或者只剩最后 1 分钟，就从头放；否则往回退 5 秒接着放。 */
    fun resumePosition(video: Video): Long {
        val pos = prefs.position(video.path)
        val dur = prefs.duration(video.path)
        if (pos < 30_000) return 0
        if (dur > 0 && pos > dur - 60_000) return 0
        return (pos - 5_000).coerceAtLeast(0)
    }

    fun progressOf(video: Video): Float {
        val dur = prefs.duration(video.path)
        if (dur <= 0) return 0f
        return (prefs.position(video.path).toFloat() / dur).coerceIn(0f, 1f)
    }

    private fun saveProgress() {
        val v = current ?: return
        if (durationMs > 0) prefs.saveDuration(v.path, durationMs)
        if (positionMs > 0) prefs.savePosition(v.path, positionMs)
    }

    /** 静音测试时每 5 秒记一次解码统计，用来确认画面/声音确实在解码。 */
    private fun logStats() {
        val media = player.media ?: return
        try {
            val st = media.stats ?: return
            Log.i(
                TAG,
                "stats ${current?.file?.name}: video decoded=${st.decodedVideo} shown=${st.displayedPictures} " +
                    "lost=${st.lostPictures} | audio decoded=${st.decodedAudio} played=${st.playedAbuffers} " +
                    "lost=${st.lostAbuffers} | vlcVolume=${player.volume}",
            )
        } finally {
            media.release()
        }
    }

    private fun updateSession() {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP,
                )
                .setState(state, positionMs, 1f)
                .build(),
        )
    }

    override fun onCleared() {
        saveProgress()
        session.release()
        player.setEventListener(null)
        player.release()
        libVlc.release()
    }

    private companion object {
        const val TAG = "Huangmei"
        val remoteKeys = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }
}
