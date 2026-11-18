package app.huangmei.player

import android.content.Context
import androidx.core.content.edit

/** 每部戏看到哪、上次看的是哪部，以及几个开关。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("huangmei", Context.MODE_PRIVATE)

    fun position(path: String): Long = sp.getLong("pos:$path", 0L)
    fun savePosition(path: String, positionMs: Long) = sp.edit { putLong("pos:$path", positionMs) }

    fun duration(path: String): Long = sp.getLong("dur:$path", 0L)
    fun saveDuration(path: String, durationMs: Long) = sp.edit { putLong("dur:$path", durationMs) }

    var lastPath: String?
        get() = sp.getString("last", null)
        set(value) = sp.edit { putString("last", value) }

    /** 打开时倒计时后自动接着放上次那部。 */
    var autoResume: Boolean
        get() = sp.getBoolean("autoResume", true)
        set(value) = sp.edit { putBoolean("autoResume", value) }

    /** 只对设备所有者生效：是否锁定在本应用。维护时可在面板里关掉。 */
    var kioskEnabled: Boolean
        get() = sp.getBoolean("kiosk", true)
        set(value) = sp.edit { putBoolean("kiosk", value) }

    /** 调试用：播放器静音，也不会自动调高系统音量。打开时屏幕顶部有红字提示。 */
    var silentTest: Boolean
        get() = sp.getBoolean("silentTest", false)
        set(value) = sp.edit { putBoolean("silentTest", value) }

    /**
     * 调试用，只能用 adb 写入：静音测试时照样解码音轨（VLC 音量仍为 0），用来验证声音通路。
     * 默认关：静音测试时 VLC 直接不解码音轨。
     */
    val silentDecodeAudio: Boolean
        get() = sp.getBoolean("silentDecodeAudio", false)

    /** 常看：视频路径，按挑选顺序。 */
    var favorites: List<String>
        get() = sp.getString("favorites", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = sp.edit { putString("favorites", value.joinToString("\n")) }

    /** 自动亮度（跟着屋里光线变），默认开。关掉后用 manualBrightness。 */
    var autoBrightness: Boolean
        get() = sp.getBoolean("autoBrightness", true)
        set(value) = sp.edit { putBoolean("autoBrightness", value) }

    /** 手动亮度 0.1~1.0，作用在本应用窗口上（锁定后奶奶只看得到这个窗口）。 */
    var manualBrightness: Float
        get() = sp.getFloat("manualBrightness", 0.7f)
        set(value) = sp.edit { putFloat("manualBrightness", value) }

    /** 已经处理过"开机锁回"的系统开机次数（Settings.Global.BOOT_COUNT）。 */
    var bootHandled: Int
        get() = sp.getInt("bootHandled", -1)
        set(value) = sp.edit(commit = true) { putInt("bootHandled", value) }

    /** 微信视频来电自动接听。锁定时会把微信加进锁定白名单，来电界面才能弹出来。 */
    var autoAnswer: Boolean
        get() = sp.getBoolean("autoAnswer", true)
        set(value) = sp.edit { putBoolean("autoAnswer", value) }

    /**
     * 只自动接听这些人（来电界面上显示的名字：有备注显示备注，没有显示昵称），一行一个。
     * 空 = 微信好友打来的视频都接（陌生人本来就打不进来）。只能用 adb 写入。
     */
    val answerOnly: List<String>
        get() = sp.getString("answerOnly", null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 来电响几秒后自动接，给奶奶留点反应时间。 */
    val answerDelayMs: Long
        get() = sp.getInt("answerDelaySec", 5).coerceIn(0, 30) * 1000L

    /** 语音通话也自动接（默认只接视频）。只能用 adb 写入。 */
    val answerVoice: Boolean
        get() = sp.getBoolean("answerVoice", false)

    /** 开始播放时，媒体音量低于这个百分比就拉上来，免得以为坏了。 */
    val minVolumePercent: Int
        get() = sp.getInt("minVolumePercent", 40)
}
