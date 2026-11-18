package app.huangmei.player

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log

class AdminReceiver : DeviceAdminReceiver()

/** 开机自动锁回黄梅戏。 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Kiosk.onBoot(context, "开机广播")
    }
}

/**
 * 锁定模式。
 * - 设备所有者（平板上用 adb 设置）：开机直接进本应用，通知栏/多任务/返回手势全禁，插电常亮，无锁屏。
 * - 不是设备所有者（手机测试）：只能用系统的"屏幕固定"，会弹确认框。
 */
object Kiosk {
    private const val TAG = "Kiosk"
    private const val HOME_ALIAS = "app.huangmei.player.HomeAlias"

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun homeAlias(ctx: Context) = ComponentName(ctx.packageName, HOME_ALIAS)

    fun isDeviceOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun lockState(ctx: Context): Int = ctx.getSystemService(ActivityManager::class.java).lockTaskModeState

    fun isHomeEnabled(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(homeAlias(ctx)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** 每次回到前台：设备所有者且没在维护，就锁上。没给文件权限时先不锁，方便家人去设置里授权。 */
    fun onResume(activity: Activity, prefs: Prefs, hasStorage: Boolean) {
        if (hasStorage && prefs.kioskEnabled && isDeviceOwner(activity)) lock(activity, prefs)
    }

    /**
     * 开机：维护面板里"退出锁定"只管到这次重启，开机一律锁回黄梅戏（家人维护完忘了开锁定也不怕）。
     * 开机广播和辅助服务连上时都会调；按系统开机次数记着，每次开机只做一次，之后家人再退出锁定不会被抢回去。
     */
    fun onBoot(ctx: Context, why: String) {
        if (!isDeviceOwner(ctx)) return
        val prefs = Prefs(ctx)
        val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot == prefs.bootHandled) return
        prefs.bootHandled = boot
        if (lockState(ctx) != ActivityManager.LOCK_TASK_MODE_NONE) return
        Log.i(TAG, "开机（$why，第 $boot 次）：恢复锁定，打开黄梅戏")
        prefs.kioskEnabled = true
        // 整套策略都要重新上：退出锁定时恢复过系统锁屏，不关掉的话开机会停在锁屏上，黄梅戏被挡在后面
        applyOwnerPolicies(ctx, enabled = true)
        safe("start home") {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun lock(activity: Activity, prefs: Prefs) {
        if (isDeviceOwner(activity)) {
            prefs.kioskEnabled = true
            // 必须先转：一旦设了锁定白名单，系统会自动把当前这个（普通方式开的）实例锁住，它就关不掉了
            if (redirectToHomeIfNeeded(activity, prefs)) return
            applyOwnerPolicies(activity, enabled = true)
            setSystemAutoBrightness(activity, prefs.autoBrightness)
            // 锁屏已经弹出来了（比如开机时）：没有密码，直接解除
            val km = activity.getSystemService(android.app.KeyguardManager::class.java)
            if (km.isKeyguardLocked && !km.isKeyguardSecure) {
                Log.i(TAG, "锁屏挡着，解除")
                safe("dismiss keyguard") { km.requestDismissKeyguard(activity, null) }
            }
        }
        if (lockState(activity) == ActivityManager.LOCK_TASK_MODE_NONE) {
            safe("startLockTask") { activity.startLockTask() }
        }
    }

    /**
     * 锁定时只保留"桌面"那一个界面实例。从图标、adb 等普通方式打开的，转到桌面实例并关掉自己，
     * 否则按 HOME 会再开一个桌面实例，变成两个播放器。
     * 这里只把桌面指向本应用、不设锁定白名单（设了会把当前实例自动锁住而关不掉），锁定交给桌面实例自己做。
     */
    fun redirectToHomeIfNeeded(activity: Activity, prefs: Prefs): Boolean {
        if (!prefs.kioskEnabled || !isDeviceOwner(activity)) return false
        if (activity.intent?.hasCategory(Intent.CATEGORY_HOME) == true) return false
        Log.i(TAG, "非桌面方式打开，转到桌面实例并关掉自己：$activity")
        val app = activity.applicationContext
        makeHome(app)
        activity.finishAndRemoveTask()
        safe("redirect to home") {
            app.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return true
    }

    /** 系统自动亮度开关（设备所有者才能改）。 */
    fun setSystemAutoBrightness(ctx: Context, on: Boolean) {
        if (Build.VERSION.SDK_INT < 28 || !isDeviceOwner(ctx)) return
        safe("brightness mode") {
            dpm(ctx).setSystemSetting(
                admin(ctx),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC.toString()
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
            )
        }
    }

    fun unlock(activity: Activity, prefs: Prefs) {
        val owner = isDeviceOwner(activity)
        if (owner) prefs.kioskEnabled = false
        safe("stopLockTask") { activity.stopLockTask() }
        if (owner) applyOwnerPolicies(activity, enabled = false)
    }

    /** 手机测试用：把本应用设为/取消桌面。设为桌面后跳到系统"默认桌面"页面去选。 */
    fun toggleHome(activity: Activity) {
        val enable = !isHomeEnabled(activity)
        setHomeEnabled(activity, enable)
        if (enable) safe("home settings") { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    /** 卸载前用：解除设备所有者。 */
    fun releaseOwner(activity: Activity, prefs: Prefs) {
        unlock(activity, prefs)
        @Suppress("DEPRECATION")
        safe("clearDeviceOwnerApp") { dpm(activity).clearDeviceOwnerApp(activity.packageName) }
    }

    private fun applyOwnerPolicies(ctx: Context, enabled: Boolean) {
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        if (enabled) {
            // 微信自动接听开着：微信也进锁定白名单，来电界面才弹得出来（不在通话时由 CallAnswerService 切回）
            val autoAnswer = Prefs(ctx).autoAnswer && isInstalled(ctx, CallGuard.WECHAT)
            val packages = if (autoAnswer) arrayOf(ctx.packageName, CallGuard.WECHAT) else arrayOf(ctx.packageName)
            safe("lockTaskPackages") { dpm.setLockTaskPackages(admin, packages) }
            if (autoAnswer) grantWeChatCallPermissions(ctx)
            if (Build.VERSION.SDK_INT >= 28) {
                // 只保留长按电源键的关机菜单
                safe("lockTaskFeatures") {
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
                }
            }
            makeHome(ctx)
            safe("keyguard") { dpm.setKeyguardDisabled(admin, true) }
            safe("stay on") {
                val plugged = BatteryManager.BATTERY_PLUGGED_AC or
                    BatteryManager.BATTERY_PLUGGED_USB or
                    BatteryManager.BATTERY_PLUGGED_WIRELESS
                dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())
            }
        } else {
            safe("clear preferred") { dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName) }
            setHomeEnabled(ctx, false)
            safe("keyguard") { dpm.setKeyguardDisabled(admin, false) }
            safe("lockTaskPackages") { dpm.setLockTaskPackages(admin, emptyArray()) }
        }
    }

    /** 自动接听开关变了：重新设锁定白名单（锁定中才需要）。 */
    fun refreshLockTaskPackages(ctx: Context, prefs: Prefs) {
        if (isDeviceOwner(ctx) && prefs.kioskEnabled && lockState(ctx) != ActivityManager.LOCK_TASK_MODE_NONE) {
            applyOwnerPolicies(ctx, enabled = true)
        }
    }

    /** 提前给微信摄像头、麦克风、通知权限，免得第一次来电弹授权框奶奶不会点。 */
    private fun grantWeChatCallPermissions(ctx: Context) {
        val perms = buildList {
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        for (p in perms) safe("grant $p") {
            val ok = dpm(ctx).setPermissionGrantState(
                admin(ctx), CallGuard.WECHAT, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            if (!ok) Log.w(TAG, "给微信 $p 失败")
        }
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** 把系统桌面固定成本应用（设备所有者）。 */
    private fun makeHome(ctx: Context) {
        setHomeEnabled(ctx, true)
        safe("preferred home") {
            dpm(ctx).addPersistentPreferredActivity(admin(ctx), homeFilter(), homeAlias(ctx))
        }
    }

    private fun setHomeEnabled(ctx: Context, enabled: Boolean) = safe("home alias") {
        ctx.packageManager.setComponentEnabledSetting(
            homeAlias(ctx),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what 失败", e)
        }
    }
}
