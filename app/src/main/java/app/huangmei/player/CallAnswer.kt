package app.huangmei.player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 微信视频来电自动接听，和锁定模式配合。
 *
 * 微信对辅助服务隐藏了界面内容（读不到"接听"按钮），所以：
 * - 来电状态、来电人：从微信的来电通知读（正文"某某邀请你视频通话"，category=call）。
 * - 点接听：确认微信来电界面（VoIP 界面）在最前面后，截一张屏，找右下方的绿色圆按钮点它。
 * - 接通的标志：音频模式变成通话模式；挂断：音频模式回到普通。
 *
 * 锁定时微信在锁定白名单里，来电界面才能弹出来；微信只许在来电/通话时停在前台，
 * 其他时候按"返回"把它退到后台（锁定时系统不让把桌面任务直接提到前面，返回键可以用）。
 */
object CallGuard {
    const val TAG = "CallAnswer"
    const val WECHAT = "com.tencent.mm"

    /** 正在响铃的微信来电（来自通知）。 */
    data class Incoming(
        val key: String,
        val name: String,
        val video: Boolean,
        val at: Long,
        val pi: PendingIntent?,
        /** 来电通知被撤掉了（微信打开来电界面时也会撤，不一定是挂断）。 */
        val removed: Boolean = false,
        val removedAt: Long = 0L,
    )

    @Volatile var incoming: Incoming? = null
    /** 自动点了接听的时间。 */
    @Volatile var answeredAt = 0L
    /** 微信最近一次切到前台的界面类名（VoIP 界面类名里有 voip）。 */
    @Volatile var lastWeChatClass: String = ""
    /** 通话结束后由我们切回黄梅戏：播放器回到前台时接着放。 */
    @Volatile private var returnFromCall = false

    fun markReturnFromCall() { returnFromCall = true }
    fun consumeReturnFromCall(): Boolean = returnFromCall.also { returnFromCall = false }

    fun audioInCall(ctx: Context): Boolean {
        val mode = ctx.getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL
    }

    /**
     * 正在响铃：收到来电通知后 60 秒内、还没接通。
     * 注意微信的来电界面一打开，来电通知就会被撤掉，所以通知撤掉不代表来电结束；
     * 只有来电界面也不在了（[ended]）才算对方挂断。
     */
    fun ringing(): Boolean {
        val c = incoming ?: return false
        return SystemClock.elapsedRealtime() - c.at < 60_000
    }

    fun voipOnTop(): Boolean = lastWeChatClass.contains("voip", ignoreCase = true)

    /** 正在响铃或通话中：这段时间微信可以停在前台。 */
    fun callActive(ctx: Context): Boolean =
        audioInCall(ctx) || ringing() || SystemClock.elapsedRealtime() - answeredAt < 15_000

    fun locked(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** 来电通知正文"某某邀请你视频通话" → 来电人；不是来电返回 null。 */
    fun parseInvite(text: String): Pair<String, Boolean>? {
        val i = text.indexOf("邀请你")
        if (i < 0 || !text.contains("通话")) return null
        return text.substring(0, i).trim() to text.contains("视频")
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm.isInteractive) return
            Log.i(TAG, "点亮屏幕")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "huangmei:call",
            ).acquire(10_000)
        }.onFailure { Log.w(TAG, "点亮屏幕失败", it) }
    }

    @Suppress("DEPRECATION")
    fun openCallScreen(ctx: Context, pi: PendingIntent) {
        runCatching {
            val opts = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
        }.onFailure { Log.w(TAG, "打开来电界面失败", it) }
    }

    // ---- 服务开关：辅助服务 / 通知监听 ----

    fun serviceComponent(ctx: Context) = ComponentName(ctx, CallAnswerService::class.java)
    fun listenerComponent(ctx: Context) = ComponentName(ctx, CallNotificationListener::class.java)

    fun isServiceOn(ctx: Context): Boolean =
        secureList(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(serviceComponent(ctx))

    fun isListenerOn(ctx: Context): Boolean =
        secureList(ctx, "enabled_notification_listeners").contains(listenerComponent(ctx))

    fun canWriteSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 辅助服务是否真的连上了（不只是设置里开着）。 */
    @Volatile var serviceConnected = false

    /**
     * 小米系统有时会把辅助服务关掉（重启、被清后台）。用 adb 给过 WRITE_SECURE_SETTINGS 的话，这里自己重新打开。
     */
    fun ensureServicesEnabled(ctx: Context, prefs: Prefs) {
        if (!prefs.autoAnswer || !canWriteSecure(ctx)) return
        val app = ctx.applicationContext
        runCatching {
            if (!isServiceOn(app)) {
                Log.i(TAG, "辅助服务没开，重新打开")
                enableService(app)
            } else if (!serviceConnected) {
                // 设置里开着却没连上：进程被杀过，系统把它记成"崩溃"不再绑定。等 5 秒还没连上就关了再开
                main.removeCallbacks(reviveCheck)
                reviveApp = app
                main.postDelayed(reviveCheck, 5_000)
            }
            if (!isListenerOn(app)) {
                Log.i(TAG, "通知监听没开，重新打开")
                addToSecureList(app, "enabled_notification_listeners", listenerComponent(app))
            }
        }.onFailure { Log.w(TAG, "重新打开服务失败", it) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var reviveApp: Context? = null
    private val reviveCheck = Runnable {
        val app = reviveApp ?: return@Runnable
        if (serviceConnected) return@Runnable
        runCatching {
            Log.i(TAG, "辅助服务开着但没连上，关掉再打开")
            removeFromSecureList(app, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, serviceComponent(app))
            main.postDelayed({ runCatching { enableService(app) } }, 1_000)
        }.onFailure { Log.w(TAG, "重启辅助服务失败", it) }
    }

    private fun enableService(app: Context) {
        addToSecureList(app, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, serviceComponent(app))
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private fun removeFromSecureList(ctx: Context, key: String, c: ComponentName) {
        val list = secureList(ctx, key)
        Settings.Secure.putString(ctx.contentResolver, key, (list - c).joinToString(":") { it.flattenToString() })
    }

    private fun secureList(ctx: Context, key: String): List<ComponentName> =
        Settings.Secure.getString(ctx.contentResolver, key).orEmpty()
            .split(':').mapNotNull { ComponentName.unflattenFromString(it) }

    private fun addToSecureList(ctx: Context, key: String, c: ComponentName) {
        val list = secureList(ctx, key)
        if (list.contains(c)) return
        Settings.Secure.putString(ctx.contentResolver, key, (list + c).joinToString(":") { it.flattenToString() })
    }
}

/** 点接听、通话结束切回黄梅戏、不在通话时把微信退到后台。 */
class CallAnswerService : AccessibilityService() {
    private val tag = CallGuard.TAG
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var answeringKey: String? = null
    private var attempts = 0
    private var opened = 0
    private var lastAttemptAt = 0L
    private var shotBusy = false
    private var wasInCall = false
    private var lastCallActiveAt = 0L
    private var lastLog = ""

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }.onFailure { Log.w(tag, "step 出错", it) }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        CallGuard.serviceConnected = true
        Log.i(tag, "辅助服务已连接")
        // 开机时辅助服务往往比开机广播先起来（小米还可能拦开机广播），这里也检查一次
        Kiosk.onBoot(this, "辅助服务")
        handler.post(tick)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        CallGuard.serviceConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CallGuard.serviceConnected = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = event.className?.toString() ?: return
        // 只记 Activity 级别的切换（微信的类名都以包名开头）
        if (event.packageName == CallGuard.WECHAT && cls.startsWith("com.tencent.mm")) {
            if (cls != CallGuard.lastWeChatClass) Log.d(tag, "微信界面：$cls")
            CallGuard.lastWeChatClass = cls
        }
    }

    private fun step() {
        if (!::prefs.isInitialized || !prefs.autoAnswer) return
        val now = SystemClock.elapsedRealtime()
        val inCall = CallGuard.audioInCall(this)
        val call = CallGuard.incoming?.takeIf { CallGuard.ringing() }

        if (call != null && !inCall) {
            if (answeringKey != call.key) {
                answeringKey = call.key
                attempts = 0
                opened = 0
                Log.i(tag, "来电：${call.name}（${if (call.video) "视频" else "语音"}）")
            }
            val onTop = wechatOnTop()
            if (call.removed && !onTop && now - call.removedAt > 3_000) {
                // 来电通知撤了、微信也不在前面：对方挂断了
                Log.i(tag, "来电结束（对方取消）")
                CallGuard.incoming = null
            } else if (shouldAnswer(call) && now - call.at >= prefs.answerDelayMs && now - lastAttemptAt > 2_500) {
                if (onTop) {
                    // 微信在最前面：截屏找绿色接听按钮（找不到就不点，所以不会误点别的界面）
                    if (attempts < 6) {
                        attempts++
                        lastAttemptAt = now
                        answerByScreenshot(attempts)
                    }
                } else if (!call.removed && opened < 3) {
                    // 来电界面没弹出来（锁定、后台弹窗限制）：替微信打开
                    opened++
                    lastAttemptAt = now
                    Log.i(tag, "来电界面不在前面，打开它")
                    call.pi?.let { CallGuard.openCallScreen(this, it) }
                }
            }
        }

        if (inCall && !wasInCall) Log.i(tag, "已接通")
        if (inCall) CallGuard.incoming = null

        // 通话结束 → 回黄梅戏，原来在放就接着放
        if (wasInCall && !inCall) {
            Log.i(tag, "通话结束")
            CallGuard.answeredAt = 0L
            CallGuard.incoming = null
            if (CallGuard.locked(this)) CallGuard.markReturnFromCall()
        }
        wasInCall = inCall

        // 锁定时：微信不在来电/通话却停在前台（挂断后剩下微信首页、对方取消、误点进去）→ 返回键退到后台。
        // 来电界面本身多等一会（通知可能还没到），免得把正在响的来电按掉
        if (CallGuard.callActive(this)) lastCallActiveAt = now
        val quietFor = now - lastCallActiveAt
        val graceMs = if (CallGuard.voipOnTop()) 20_000 else 2_000
        if (CallGuard.locked(this) && quietFor > graceMs && wechatOnTop()) {
            logOnce("锁定中微信不在通话却在前台（${CallGuard.lastWeChatClass}），按返回")
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            lastLog = ""
        }
    }

    private fun wechatOnTop(): Boolean =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root?.packageName == CallGuard.WECHAT

    private fun shouldAnswer(call: CallGuard.Incoming): Boolean {
        if (!call.video && !prefs.answerVoice) {
            logOnce("语音来电，按设置不自动接")
            return false
        }
        val only = prefs.answerOnly
        if (only.isNotEmpty() && only.none { it == call.name }) {
            logOnce("来电人「${call.name}」不在名单 $only 里，不自动接")
            return false
        }
        return true
    }

    /** 截屏找来电界面右下方的绿色接听按钮。 */
    private fun answerByScreenshot(attempt: Int) {
        if (Build.VERSION.SDK_INT < 30 || shotBusy) return
        shotBusy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                shotBusy = false
                val hw = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bmp == null) return
                val spot = findGreenButton(bmp)
                val w = bmp.width
                val h = bmp.height
                bmp.recycle()
                if (spot == null) {
                    Log.w(tag, "第 $attempt 次：截图里没找到绿色接听按钮（${w}x$h）")
                    return
                }
                Log.i(tag, "第 $attempt 次点接听：(${spot.first}, ${spot.second})")
                CallGuard.answeredAt = SystemClock.elapsedRealtime()
                tap(spot.first, spot.second)
            }

            override fun onFailure(errorCode: Int) {
                shotBusy = false
                Log.w(tag, "截屏失败 $errorCode")
            }
        })
    }

    /**
     * 微信接听按钮是实心绿圆（约 RGB 103,198,125，平板上直径约 170 像素）。在下半屏找绿色像素最密的一团，
     * 要求够大、够圆够实，免得点到画面里的绿色东西或微信首页的小图标。
     */
    private fun findGreenButton(bmp: Bitmap): Pair<Float, Float>? {
        val w = bmp.width
        val h = bmp.height
        val step = 6
        val row = IntArray(w)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in h / 2 until h step step) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val c = row[x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (g > 150 && g - r > 60 && g - b > 40) {
                    xs += x
                    ys += y
                }
            }
        }
        if (xs.size < 40) return null
        // 以最右下方那团为准：先取中位数附近，再在它周围 150 像素内重新求中心
        val mx = xs.sorted()[xs.size / 2]
        val my = ys.sorted()[ys.size / 2]
        val near = xs.indices.filter { kotlin.math.abs(xs[it] - mx) < 150 && kotlin.math.abs(ys[it] - my) < 150 }
        if (near.isEmpty()) return null
        val minX = near.minOf { xs[it] }
        val maxX = near.maxOf { xs[it] }
        val minY = near.minOf { ys[it] }
        val maxY = near.maxOf { ys[it] }
        val bw = maxX - minX + step
        val bh = maxY - minY + step
        val minSize = minOf(w, h) / 25 // 1880 高的屏约 75 像素
        val fill = near.size.toFloat() / ((bw / step) * (bh / step)).coerceAtLeast(1)
        Log.d(tag, "绿色团 n=${near.size} 框=${bw}x$bh 填充=${"%.2f".format(fill)}")
        if (bw < minSize || bh < minSize || fill < 0.5f || bw > bh * 1.6f || bh > bw * 1.6f) return null
        return near.map { xs[it] }.average().toFloat() to near.map { ys[it] }.average().toFloat()
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(),
            null,
            null,
        )
    }

    private fun logOnce(msg: String) {
        if (msg != lastLog) {
            lastLog = msg
            Log.i(tag, msg)
        }
    }
}

/** 收微信来电通知：记下来电人，点亮屏幕。来电界面没弹出来时由 CallAnswerService 替它打开。 */
class CallNotificationListener : NotificationListenerService() {
    private val tag = CallGuard.TAG

    override fun onListenerConnected() {
        Log.i(tag, "通知监听已连接")
        // 通知监听被杀后系统会自己重连，顺便看一眼辅助服务
        CallGuard.ensureServicesEnabled(this, Prefs(this))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != CallGuard.WECHAT) return
        if (!Prefs(this).autoAnswer) return
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        Log.d(tag, "微信通知 title=$title text=$text category=${n.category} fsi=${n.fullScreenIntent != null}")
        val invite = CallGuard.parseInvite(text) ?: return
        if (CallGuard.incoming?.key == sbn.key) return
        CallGuard.incoming = CallGuard.Incoming(
            key = sbn.key,
            name = invite.first,
            video = invite.second,
            at = SystemClock.elapsedRealtime(),
            pi = n.fullScreenIntent ?: n.contentIntent,
        )
        CallGuard.wakeScreen(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != CallGuard.WECHAT) return
        val c = CallGuard.incoming ?: return
        if (c.key == sbn.key && !c.removed) {
            Log.i(tag, "来电通知撤了（微信打开了来电界面，或对方挂断）")
            CallGuard.incoming = c.copy(removed = true, removedAt = SystemClock.elapsedRealtime())
        }
    }
}
