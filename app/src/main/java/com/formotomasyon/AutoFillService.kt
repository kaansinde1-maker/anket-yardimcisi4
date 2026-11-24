package com.formotomasyon

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Erişilebilirlik servisi: yüzen butonu yönetir, hedef uygulama ön plandayken
 * [FormFiller] ile formu doldurur, hata olursa raporu panoya kopyalar.
 */
class AutoFillService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoFillService? = null
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val FAILURE_FLASH_MS = 2500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventCounter = AtomicLong(0)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var cfg: Cfg
    private lateinit var wm: WindowManager
    private lateinit var screen: ScreenReader
    private lateinit var filler: FormFiller
    private lateinit var reports: ReportBuilder
    private lateinit var widget: FloatingWidget
    private lateinit var panel: SettingsPanel

    private var running = false
    private var lastForeign = ""
    private var imePackages: Set<String> = emptySet()
    private var lastReport = "Henüz rapor yok."

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cfg = Cfg(this)
        wm = getSystemService(WindowManager::class.java)
        createComponents()
        imePackages = getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }.toSet()
        rootInActiveWindow?.packageName?.toString()?.let { if (!isIgnored(it)) lastForeign = it }
        if (Settings.canDrawOverlays(this)) {
            showWidget()
        } else {
            toast("Overlay izni yok. Uygulamayı açıp izin ver.")
        }
        updateVisibility()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        if (::widget.isInitialized) widget.remove()
        if (::panel.isInitialized) panel.dismiss()
        instance = null
        super.onDestroy()
    }

    private fun createComponents() {
        screen = ScreenReader(this, ::targetPackage)
        filler = FormFiller(screen, cfg, eventCounter)
        reports = ReportBuilder(screen, cfg, ::targetPackage) { lastForeign }
        widget = FloatingWidget(
            context = this,
            wm = wm,
            onTap = ::startFill,
            onLongPress = { panel.show() },
            onAdjust = ::adjustDaireNo
        )
        panel = SettingsPanel(this, wm, cfg, object : SettingsPanel.Host {
            override val foregroundPackage: String get() = lastForeign
            override val lastReport: String get() = this@AutoFillService.lastReport
            override fun dumpTarget(): String = screen.dumpTarget()
            override fun copyToClipboard(text: String) = this@AutoFillService.copyToClipboard(text)
            override fun toast(message: String) = this@AutoFillService.toast(message)
            override fun onSaved() {
                refreshLabel()
                updateVisibility()
            }
        })
    }

    // ---- olaylar ve ön plan takibi

    private fun isIgnored(pkg: String) = pkg == packageName || pkg in imePackages || pkg == SYSTEM_UI_PACKAGE

    private fun targetPackage(): String = cfg.targetPkg.ifBlank { lastForeign }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> eventCounter.incrementAndGet()
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString()
            if (pkg != null && !isIgnored(pkg)) {
                lastForeign = pkg
                updateVisibility()
            }
        }
        if (::cfg.isInitialized && !widget.isShown && Settings.canDrawOverlays(this)) {
            showWidget()
            updateVisibility()
        }
    }

    private fun updateVisibility() {
        val target = cfg.targetPkg
        widget.setVisible(target.isBlank() || lastForeign == target)
    }

    // ---- yüzen buton

    private fun showWidget() {
        widget.show()
        refreshLabel()
    }

    private fun refreshLabel() {
        widget.setLabel(cfg.daireNo.ifBlank { "-" })
    }

    private fun adjustDaireNo(delta: Int) {
        val current = cfg.daireNo
        val number = current.toIntOrNull()
        if (current.isBlank()) {
            cfg.daireNo = "1"
        } else if (number == null) {
            toast("Daire No sayı değil: $current")
            return
        } else {
            cfg.daireNo = (number + delta).coerceAtLeast(1).toString()
        }
        refreshLabel()
    }

    // ---- form doldurma

    private fun startFill() {
        if (running) return
        if (cfg.targetPkg.isBlank()) {
            toast("Önce hedef uygulamayı ayarla (butona uzun bas).")
            return
        }
        running = true
        widget.paint(FloatingWidget.State.RUNNING)
        scope.launch {
            try {
                filler.run()
                toast("Tamamlandı")
                widget.paint(FloatingWidget.State.IDLE)
            } catch (f: StepFail) {
                reportFailure(f)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                reportFailure(StepFail(filler.currentStep, "Beklenmeyen hata", "-", t.toString()))
            } finally {
                running = false
            }
        }
    }

    private fun reportFailure(failure: StepFail) {
        lastReport = reports.build(failure, filler.listInfo)
        copyToClipboard(lastReport)
        toast("Hata: ${failure.step} — ${failure.action}. Rapor panoya kopyalandı.")
        widget.paint(FloatingWidget.State.FAILED)
        handler.postDelayed({ widget.paint(FloatingWidget.State.IDLE) }, FAILURE_FLASH_MS)
    }

    // ---- yardımcılar

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("form-otomasyon", text))
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
