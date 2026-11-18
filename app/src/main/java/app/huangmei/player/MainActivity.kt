package app.huangmei.player

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.huangmei.player.ui.App
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private companion object {
        /** 当前进程里活着的界面实例，用来保证锁定时只有一个。 */
        val instances = mutableListOf<WeakReference<MainActivity>>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁定时不是以桌面方式打开的：转到桌面实例（这里还没创建 ViewModel，不会多开一个播放器）
        if (Kiosk.redirectToHomeIfNeeded(this, Prefs(this))) return
        closeOtherInstancesIfHome()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent { App(vm) }
    }

    override fun onDestroy() {
        instances.removeAll { it.get().let { a -> a == null || a === this } }
        super.onDestroy()
    }

    /** 锁定时以桌面方式打开：把之前用普通方式开的实例都关掉，只留这一个播放器。 */
    private fun closeOtherInstancesIfHome() {
        instances.removeAll { it.get() == null }
        instances.add(WeakReference(this))
        val isHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        if (!isHome || !Prefs(this).kioskEnabled || !Kiosk.isDeviceOwner(this)) return
        instances.mapNotNull { it.get() }
            .filter { it !== this && !it.isFinishing }
            .forEach {
                Log.i("Kiosk", "桌面实例启动，关掉多余实例：$it")
                it.finishAndRemoveTask()
            }
    }

    override fun onStart() {
        super.onStart()
        vm.onVisible(true)
        vm.refresh()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applyBrightness()
        Kiosk.onResume(this, vm.prefs, Storage.hasAccess(this))
        CallGuard.ensureServicesEnabled(this, vm.prefs)
    }

    override fun onStop() {
        vm.onVisible(false)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Android 9 及以下的运行时权限回调")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        vm.refresh()
    }

    /** 遥控器 / 键盘的播放、上下一部；音量键交给系统。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.isRemoteKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.onRemoteKey(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 自动亮度：交给系统；手动：按维护面板里设的值调本窗口亮度。 */
    fun applyBrightness() {
        val prefs = vm.prefs
        Kiosk.setSystemAutoBrightness(this, prefs.autoBrightness)
        window.attributes = window.attributes.apply {
            screenBrightness = if (prefs.autoBrightness) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                prefs.manualBrightness
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
