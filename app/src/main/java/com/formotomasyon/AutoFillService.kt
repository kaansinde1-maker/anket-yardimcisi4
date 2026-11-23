package com.formotomasyon

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

class AutoFillService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoFillService? = null
        const val STEP_MS = 3000L      // her bekleme için zaman aşımı
        const val MAH_MS = 12000L      // mahalle taraması toplam üst sınırı
        private val NEXT = setOf("ileri", "devam", "devam et", "next", "sonraki")
        private val STEPS = listOf("İlçe", "Mahalle", "Cadde", "Sokak", "Bina Adı", "Bina No", "Daire No", "Bina Tipi")
        private const val CITY_TITLE = "aslen nereli"
        private const val CITY = "Manisa"
        private const val CITY_SCROLL_TRIES = 14
        private val CITY_STEPS = listOf(CITY_TITLE)
        private val NOTE_STEPS = listOf("Anketör notu", "Denetçi notu")
        private const val BLUE = 0xFF1565C0.toInt()
        private const val ORANGE = 0xFFEF6C00.toInt()
        private const val RED = 0xFFC62828.toInt()
    }

    private class StepFail(
        val step: String, val action: String, val expected: String, val found: String
    ) : Exception("$step | $action")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventCounter = AtomicLong(0)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cfg: Cfg
    private lateinit var wm: WindowManager
    private var widget: LinearLayout? = null
    private var widgetLp: WindowManager.LayoutParams? = null
    private var mainBtn: TextView? = null
    private var panel: View? = null
    private var running = false
    private var lastForeign = ""
    private var imePkgs: Set<String> = emptySet()
    private var curStep = "-"
    private var lastReport = "Henüz rapor yok."
    private var mahInfo = ""

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cfg = Cfg(this)
        wm = getSystemService(WindowManager::class.java)
        val imm = getSystemService(InputMethodManager::class.java)
        imePkgs = imm.enabledInputMethodList.map { it.packageName }.toSet()
        rootInActiveWindow?.packageName?.toString()?.let { if (!ignored(it)) lastForeign = it }
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
        widget?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        instance = null
        super.onDestroy()
    }

    private fun ignored(pkg: String) = pkg == packageName || pkg in imePkgs || pkg == "com.android.systemui"

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
            if (pkg != null && !ignored(pkg)) {
                lastForeign = pkg
                updateVisibility()
            }
        }
        if (widget == null && ::cfg.isInitialized && Settings.canDrawOverlays(this)) {
            showWidget()
            updateVisibility()
        }
    }

    private fun updateVisibility() {
        val t = cfg.targetPkg
        widget?.visibility = if (t.isBlank() || lastForeign == t) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ yüzen buton

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun showWidget() {
        if (widget != null) return
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE6212121.toInt(), 30 * d)
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        fun small(t: String) = TextView(this).apply {
            text = t; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        }
        val minus = small("−").apply { setOnClickListener { bump(-1) } }
        val plus = small("+").apply { setOnClickListener { bump(1) } }
        val main = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
        }
        mainBtn = main
        box.addView(minus)
        box.addView(main)
        box.addView(plus)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * d).toInt()
            y = (280 * d).toInt()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longFired = false
        val longRun = Runnable {
            if (!dragging) { longFired = true; openPanel() }
        }
        main.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longFired = false
                    handler.postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        handler.removeCallbacks(longRun)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(box, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRun)
                    if (!dragging && !longFired) startFill()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRun)
                    true
                }
                else -> false
            }
        }
        widget = box
        widgetLp = lp
        wm.addView(box, lp)
        paintMain(BLUE)
        refreshLabel()
    }

    private fun paintMain(color: Int) {
        mainBtn?.background = rounded(color, 24 * resources.displayMetrics.density)
    }

    private fun refreshLabel() {
        mainBtn?.text = "Daire " + cfg.daireNo.ifBlank { "-" }
    }

    private fun bump(delta: Int) {
        val cur = cfg.daireNo
        val n = cur.toIntOrNull()
        if (cur.isBlank()) {
            cfg.daireNo = "1"
        } else if (n == null) {
            toast("Daire No sayı değil: $cur")
            return
        } else {
            cfg.daireNo = (n + delta).coerceAtLeast(0).toString()
        }
        refreshLabel()
    }

    // ------------------------------------------------------------------ ayar penceresi

    private fun openPanel() {
        if (panel != null) return
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cBg = 0xFF121212.toInt()
        val cField = 0xFF1E1E1E.toInt()
        val cText = 0xFFEEEEEE.toInt()
        val cHint = 0xFF777777.toInt()
        val cLabel = 0xFFB0BEC5.toInt()
        val cBtn = 0xFF37474F.toInt()

        fun label(t: String) = TextView(ctx).apply {
            text = t; setTextColor(cLabel); textSize = 11f
            setPadding(0, dp(6), 0, dp(1))
        }
        fun edit(hint: String, v: String, numeric: Boolean = false) = EditText(ctx).apply {
            this.hint = hint; setText(v); setTextColor(cText); setHintTextColor(cHint); textSize = 13f
            background = rounded(cField, 6 * d)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setSingleLine(true)
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        fun btn(t: String, color: Int, size: Float = 13f, onClick: () -> Unit) = Button(ctx).apply {
            text = t; textSize = size; isAllCaps = false; setTextColor(Color.WHITE)
            background = rounded(color, 8 * d)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
        val full = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }

        // ---- alanlar
        var tmpTarget = cfg.targetPkg
        val mah = edit("Mahalle", cfg.mahalle)
        val cad = edit("Cadde", cfg.cadde)
        val sok = edit("Sokak", cfg.sokak)
        val bad = edit("Bina Adı", cfg.binaAdi)
        val bno = edit("Bina No", cfg.binaNo)
        val dno = edit("Daire No", cfg.daireNo, numeric = true)
        val ilce = Switch(ctx).apply {
            text = "İlçe: 2. şık"; textSize = 12f
            setTextColor(cText); isChecked = cfg.ilceIkinci
        }

        // Bina No: baştaki sayıyı değiştirir, kalanı korur (23/1 -> 24/1)
        fun bumpBno(delta: Int) {
            val cur = bno.text.toString().trim()
            val m = Regex("^(\\d+)(.*)$").find(cur)
            if (cur.isEmpty()) {
                bno.setText("1")
            } else if (m == null) {
                toast("Bina No sayıyla başlamıyor: $cur")
            } else {
                val n = (m.groupValues[1].toLongOrNull() ?: 0L) + delta
                bno.setText(maxOf(n, 0L).toString() + m.groupValues[2])
            }
        }
        val bnoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        bnoRow.addView(
            btn("−", cBtn, 16f) { bumpBno(-1) }.apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(dp(34), dp(34))
        )
        bnoRow.addView(bno, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })
        bnoRow.addView(
            btn("+", cBtn, 16f) { bumpBno(1) }.apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(dp(34), dp(34))
        )

        // ---- sol sütun (ters sıra: Daire No en üstte)
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("Daire No")); left.addView(dno, full())
        left.addView(label("Bina No")); left.addView(bnoRow, full())
        left.addView(label("Bina Adı")); left.addView(bad, full())
        left.addView(label("Sokak")); left.addView(sok, full())
        left.addView(label("Cadde")); left.addView(cad, full())
        left.addView(label("Mahalle")); left.addView(mah, full())

        // ---- sağ sütun: Bina Tipi kutucukları (seçiliye tekrar dokununca seçim kalkar)
        var selTipi = cfg.binaTipi
        val chips = ArrayList<Pair<TextView, String>>()
        fun paintChips() {
            for ((tv, o) in chips) {
                val on = o == selTipi
                tv.background = rounded(if (on) 0xFF1565C0.toInt() else 0xFF2A2A2A.toInt(), 8 * d)
                tv.setTextColor(if (on) Color.WHITE else 0xFFCCCCCC.toInt())
            }
        }
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(label("Bina Tipi"))
        for (o in BinaTipleri.liste) {
            val tv = TextView(ctx).apply {
                text = o; textSize = 12f; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { selTipi = if (selTipi == o) "" else o; paintChips() }
            }
            chips.add(tv to o)
            right.addView(tv, full().apply { topMargin = dp(4) })
        }
        paintChips()

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        body.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        body.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })

        // ---- Gelişmiş (kapalı gelir): hedef uygulama + debug
        val targetTv = label("Hedef: " + tmpTarget.ifBlank { "— (seçilmedi)" })
        val targetBtn = btn("Şu anki uygulamayı hedef yap", cBtn, 12f) {
            if (lastForeign.isBlank()) {
                toast("Henüz bir uygulama algılanmadı. Anket uygulamasına geçip tekrar dene.")
            } else {
                tmpTarget = lastForeign
                targetTv.text = "Hedef: $tmpTarget"
            }
        }
        val adv = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(10), 0, dp(10), dp(8))
        }
        adv.addView(targetTv)
        adv.addView(targetBtn, full().apply { topMargin = dp(4) })
        adv.addView(
            btn("Ağacı kopyala (debug)", cBtn, 12f) { copyToClipboard(dumpTarget()); toast("Ekran ağacı panoya kopyalandı") },
            full().apply { topMargin = dp(6) }
        )
        adv.addView(
            btn("Son raporu kopyala", cBtn, 12f) { copyToClipboard(lastReport); toast("Son rapor panoya kopyalandı") },
            full().apply { topMargin = dp(6) }
        )
        val advBtn = btn("Gelişmiş ▾", cBtn, 12f) { }
        advBtn.setOnClickListener {
            val open = adv.visibility != View.VISIBLE
            adv.visibility = if (open) View.VISIBLE else View.GONE
            advBtn.text = if (open) "Gelişmiş ▴" else "Gelişmiş ▾"
        }
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        bottomRow.addView(ilce, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottomRow.addView(advBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- en üst: Kaydet / İptal (sabit)
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(2))
        }
        val save = btn("Kaydet", 0xFF2E7D32.toInt()) {
            cfg.targetPkg = tmpTarget
            cfg.mahalle = mah.text.toString().trim()
            cfg.cadde = cad.text.toString().trim()
            cfg.sokak = sok.text.toString().trim()
            cfg.binaAdi = bad.text.toString().trim()
            cfg.binaNo = bno.text.toString().trim()
            cfg.daireNo = dno.text.toString().trim()
            cfg.binaTipi = selTipi
            cfg.ilceIkinci = ilce.isChecked
            cfg.mahCache = ""
            refreshLabel(); updateVisibility(); closePanel()
            toast("Kaydedildi")
        }
        val cancel = btn("İptal", 0xFF424242.toInt()) { closePanel() }
        topRow.addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        topRow.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body)
        content.addView(bottomRow)
        content.addView(adv)
        val sv = ScrollView(ctx).apply { addView(content) }
        val rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cBg, 16 * d)
        }
        rootView.addView(topRow)
        rootView.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.96).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(16)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = rootView
        wm.addView(rootView, lp)
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    // ------------------------------------------------------------------ otomasyon akışı

    private fun startFill() {
        if (running) return
        if (cfg.targetPkg.isBlank()) {
            toast("Önce hedef uygulamayı ayarla (butona uzun bas).")
            return
        }
        running = true
        paintMain(ORANGE)
        scope.launch {
            try {
                runFlow()
                toast("Tamamlandı")
                paintMain(BLUE)
            } catch (f: StepFail) {
                fail(f)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(StepFail(curStep, "Beklenmeyen hata", "-", t.toString()))
            } finally {
                running = false
            }
        }
    }

    private fun matchesTitle(n: String, t: String) =
        n == t || n.startsWith("$t ") || n.endsWith(" $t") || n.contains(" $t ")

    /** Ekranda şehir ya da not sorusu varsa onun adımları, aksi halde ana akış (İlçe'den). */
    private fun stepsForCurrentScreen(): List<String> {
        val texts = allNodes().filter { !it.isEditable }.map { Txt.norm(Nodes.label(it)) }
        fun has(keyword: String): Boolean {
            val t = Txt.norm(keyword)
            return texts.any { matchesTitle(it, t) }
        }
        return when {
            has(CITY_TITLE) -> CITY_STEPS
            has(NOTE_STEPS[0]) -> NOTE_STEPS
            has(NOTE_STEPS[1]) -> NOTE_STEPS.drop(1)
            else -> STEPS
        }
    }

    private suspend fun runFlow() {
        val c = cfg
        val steps = stepsForCurrentScreen()
        if (steps === STEPS) {
            if (c.mahalle.isBlank()) throw StepFail("Ayarlar", "Mahalle kontrolü", "Mahalle dolu olmalı", "boş")
            if (c.binaTipi.isBlank()) throw StepFail("Ayarlar", "Bina Tipi kontrolü", "Bina Tipi seçili olmalı", "boş")
        }
        for ((i, title) in steps.withIndex()) {
            curStep = "${i + 1}/${steps.size} $title"
            awaitTitle(title)
            when (title) {
                "İlçe" -> selectIlce()
                "Mahalle" -> selectMahalle(c.mahalle)
                "Cadde" -> typeText(c.cadde.ifBlank { "." })
                "Sokak" -> typeText(c.sokak.ifBlank { "." })
                "Bina Adı" -> typeText(c.binaAdi.ifBlank { "." })
                "Bina No" -> typeText(c.binaNo.ifBlank { "." })
                "Daire No" -> typeText(c.daireNo.ifBlank { "." })
                "Bina Tipi" -> pickOption(c.binaTipi)
                CITY_TITLE -> pickCity(CITY)
                NOTE_STEPS[0], NOTE_STEPS[1] -> typeText(".")
            }
            clickNext()
        }
    }

    // ---- bekleme yardımcıları (olay tabanlı + kısa kalp atışı)

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (cond()) return true
            val left = end - SystemClock.uptimeMillis()
            if (left <= 0) return false
            val c = eventCounter.get()
            val slice = minOf(left, 120L)
            var waited = 0L
            while (eventCounter.get() == c && waited < slice) { delay(15); waited += 15 }
        }
    }

    private suspend fun <T> waitFor(ms: Long, f: () -> T?): T? {
        var r: T? = null
        waitUntil(ms) { r = f(); r != null }
        return r
    }

    // ---- düğüm erişimi

    private fun targetPkg(): String = cfg.targetPkg.ifBlank { lastForeign }

    private fun targetRoots(): List<AccessibilityNodeInfo> {
        val pkg = targetPkg()
        val res = ArrayList<AccessibilityNodeInfo>()
        for (w in windows) {
            val r = w.root ?: continue
            if (r.packageName?.toString() == pkg) res.add(r)
        }
        if (res.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) res.add(it) }
        }
        return res
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (r in targetRoots()) Nodes.walk(r, out)
        return out
    }

    private fun visibleSig(): String =
        allNodes().filter { it.isVisibleToUser }.joinToString("|") { Nodes.label(it) }

    private fun baselineLabels(): Set<String> =
        allNodes().map { Txt.norm(Nodes.label(it)) }.filter { it.isNotEmpty() }.toSet()

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        val c = Nodes.clickableSelfOrAncestor(n) ?: n
        return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- ekran başlığı

    private fun hasTitle(t: String): Boolean = allNodes().any {
        if (it.isEditable) return@any false
        val n = Txt.norm(Nodes.label(it))
        n == t || n.startsWith("$t ") || n.endsWith(" $t") || n.contains(" $t ")
    }

    private suspend fun awaitTitle(title: String) {
        val t = Txt.norm(title)
        if (!waitUntil(STEP_MS) { hasTitle(t) }) {
            throw StepFail(curStep, "Ekran başlığı bekleniyor", "'$title' başlıklı ekran", "${STEP_MS / 1000} sn içinde görünmedi")
        }
    }

    // ---- metin kutusu

    private suspend fun typeText(text: String) {
        val et = waitFor(STEP_MS) { allNodes().firstOrNull { it.isEditable && it.isVisibleToUser } }
            ?: throw StepFail(curStep, "Metin kutusu aranıyor", "Görünür EditText", "bulunamadı")
        et.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = et.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) throw StepFail(curStep, "ACTION_SET_TEXT", "true dönmesi", "false döndü")
        val applied = waitUntil(1500) { et.refresh(); et.text?.toString() == text }
        if (!applied) {
            throw StepFail(curStep, "Yazılan metin doğrulanıyor", "'$text'", "'${et.text}'")
        }
    }

    // ---- dropdown

    private fun findDropdown(): AccessibilityNodeInfo? {
        val nodes = allNodes().filter { it.isVisibleToUser }
        nodes.firstOrNull { it.className?.toString()?.contains("Spinner") == true }?.let { return it }
        nodes.firstOrNull { Txt.norm(Nodes.label(it)) == "seciniz" }?.let { return it }
        return nodes.firstOrNull { (it.contentDescription?.toString() ?: "").lowercase(Locale.ROOT).contains("drop") }
    }

    private suspend fun openDropdown(baseline: Set<String>): List<AccessibilityNodeInfo> {
        val dd = waitFor(STEP_MS) { findDropdown() }
            ?: throw StepFail(curStep, "Dropdown aranıyor", "Spinner veya 'Seçiniz' düğümü", "bulunamadı")
        if (!clickNode(dd)) throw StepFail(curStep, "Dropdown tıklanıyor", "ACTION_CLICK true", "false")
        val items = waitFor(STEP_MS) { listCandidates(baseline).takeIf { it.isNotEmpty() } }
            ?: throw StepFail(curStep, "Liste açılıyor", "Yeni liste öğeleri", "liste görünmedi")
        delay(150)
        return if (listCandidates(baseline).isNotEmpty()) listCandidates(baseline) else items
    }

    /** Dropdown açılınca ekrana yeni gelen (açılmadan önce olmayan) görünür metin düğümleri. */
    private fun listCandidates(baseline: Set<String>): List<AccessibilityNodeInfo> =
        allNodes().filter {
            it.isVisibleToUser && (it.childCount == 0 || !it.text.isNullOrBlank())
        }.filter {
            val l = Txt.norm(Nodes.label(it)); l.isNotEmpty() && l !in baseline
        }

    private suspend fun scrollList(baseline: Set<String>, forward: Boolean): Boolean {
        val items = listCandidates(baseline)
        val first = items.firstOrNull() ?: return false
        val list = Nodes.scrollableAncestor(first) ?: return false
        val before = items.joinToString("|") { Nodes.label(it) }
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (!list.performAction(act)) return false
        return waitUntil(1200) { listCandidates(baseline).joinToString("|") { Nodes.label(it) } != before }
    }

    private suspend fun awaitSelectionApplied() {
        val ok = waitUntil(STEP_MS) { allNodes().none { Txt.norm(Nodes.label(it)) == "seciniz" } }
        if (!ok) throw StepFail(curStep, "Seçim uygulandı mı kontrolü", "'Seçiniz' yazısı kaybolmalı", "hâlâ ekranda")
    }

    private fun radioButtons(): List<AccessibilityNodeInfo> =
        allNodes().filter { it.isVisibleToUser && it.className?.toString()?.contains("RadioButton") == true }
            .sortedWith(compareBy({ Nodes.top(it) }, { Nodes.left(it) }))

    private suspend fun selectIlce() {
        val idx0 = if (cfg.ilceIkinci) 1 else 0
        // 1) İlçe şık (RadioButton) olarak gelirse: sıradaki şıkka tıkla
        val radios = waitFor(1500) { radioButtons().takeIf { it.isNotEmpty() } }
        if (radios != null) {
            val pickR = radios.getOrNull(idx0) ?: throw StepFail(
                curStep, "İlçe şıkkı", "${idx0 + 1}. şık", "ekranda ${radios.size} şık: " + radios.joinToString { Nodes.label(it) }
            )
            mahInfo = "İlçe şıkları: " + radios.joinToString { Nodes.label(it) } + " → seçilen: ${idx0 + 1}. (${Nodes.label(pickR)})"
            if (!clickNode(pickR)) throw StepFail(curStep, "İlçe şıkkı tıklanıyor", "ACTION_CLICK true", "false")
            val checked = waitUntil(STEP_MS) { pickR.refresh(); pickR.isChecked }
            if (!checked) throw StepFail(curStep, "İlçe şıkkı seçildi mi kontrolü", "isChecked=true", "false")
            return
        }
        // 2) Aksi halde dropdown olarak dene
        val baseline = baselineLabels()
        val items = openDropdown(baseline)
        val rows = items
            .sortedWith(compareBy({ Nodes.top(it) }, { Nodes.left(it) }))
            .filter { Txt.norm(Nodes.label(it)) != "seciniz" }
            .distinctBy { Txt.norm(Nodes.label(it)) }
        val idx = if (cfg.ilceIkinci) 1 else 0
        val pick = rows.getOrNull(idx) ?: throw StepFail(
            curStep, "İlçe seçeneği", "${idx + 1}. madde", "listede ${rows.size} madde: " + rows.joinToString { Nodes.label(it) }
        )
        mahInfo = "İlçe listesi: " + rows.joinToString { Nodes.label(it) } + " → seçilen: ${Nodes.label(pick)}"
        if (!clickNode(pick)) throw StepFail(curStep, "İlçe maddesi tıklanıyor", "ACTION_CLICK true", "false")
        awaitSelectionApplied()
    }

    private suspend fun selectMahalle(target: String) {
        val baseline = baselineLabels()
        openDropdown(baseline)
        val tn = Txt.stripMah(Txt.norm(target))
        val deadline = SystemClock.uptimeMillis() + MAH_MS
        val seen = LinkedHashMap<String, Pair<String, Double>>()
        var net = 0
        var scrolls = 0
        val t0 = SystemClock.uptimeMillis()

        val cache = cfg.mahCache.split("|")
        if (cache.size == 2 && cache[0] == tn) {
            repeat(cache[1].toIntOrNull() ?: 0) { if (scrollList(baseline, true)) net++ }
        }

        var exact: AccessibilityNodeInfo? = null
        while (true) {
            for (n in listCandidates(baseline)) {
                val key = Txt.stripMah(Txt.norm(Nodes.label(n)))
                if (key.isEmpty()) continue
                if (!seen.containsKey(key)) seen[key] = Nodes.label(n) to Txt.score(tn, key)
                if (key == tn) { exact = n; break }
            }
            if (exact != null) break
            if (SystemClock.uptimeMillis() > deadline) break
            if (!scrollList(baseline, true)) break
            net++; scrolls++
        }
        fun summarize(extra: String) {
            val top = seen.entries.sortedByDescending { it.value.second }.take(8)
                .joinToString("\n") { "   %.2f  %s".format(Locale.US, it.value.second, it.value.first) }
            mahInfo = "Mahalle hedef='$target' (norm='$tn'), görülen=${seen.size}, kaydırma=$scrolls, " +
                "süre=${SystemClock.uptimeMillis() - t0} ms, $extra\n   En yakın adaylar:\n$top"
        }

        val ex = exact
        if (ex != null) {
            summarize("tam eşleşme")
            if (!clickNode(ex)) throw StepFail(curStep, "Mahalle tıklanıyor", "ACTION_CLICK true", "false")
            cfg.mahCache = "$tn|$net"
            awaitSelectionApplied()
            return
        }

        val ranked = seen.entries.sortedByDescending { it.value.second }
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        summarize("tam eşleşme yok")
        if (best == null) throw StepFail(curStep, "Mahalle listesi okunuyor", "En az 1 mahalle", "hiç öğe okunamadı")
        if (best.value.second < 0.80) {
            throw StepFail(curStep, "Mahalle eşleşmesi", "skor ≥ 0.80", "en iyi: '${best.value.first}' (%.2f)".format(Locale.US, best.value.second))
        }
        if (second != null && best.value.second - second.value.second < 0.07) {
            throw StepFail(
                curStep, "Mahalle eşleşmesi belirsiz", "en iyi ile 2. arasında ≥ 0.07 fark",
                "'${best.value.first}' (%.2f) / '${second.value.first}' (%.2f)".format(Locale.US, best.value.second, second.value.second)
            )
        }
        // en iyi adaya geri dön
        val backDeadline = SystemClock.uptimeMillis() + 4000
        while (true) {
            val hit = listCandidates(baseline).firstOrNull { Txt.stripMah(Txt.norm(Nodes.label(it))) == best.key }
            if (hit != null) {
                if (!clickNode(hit)) throw StepFail(curStep, "Mahalle tıklanıyor", "ACTION_CLICK true", "false")
                cfg.mahCache = "$tn|$net"
                awaitSelectionApplied()
                return
            }
            if (SystemClock.uptimeMillis() > backDeadline || !scrollList(baseline, false)) {
                throw StepFail(curStep, "Eşleşen mahalleye geri dönülüyor", "'${best.value.first}' görünür olmalı", "bulunamadı")
            }
            net--
        }
    }

    // ---- şık seçimi (Bina Tipi)

    private suspend fun pickOption(label: String) {
        val t = Txt.norm(label)
        var found: AccessibilityNodeInfo? = null
        for (i in 0..6) {
            found = allNodes().firstOrNull { it.isVisibleToUser && Txt.norm(Nodes.label(it)) == t }
            if (found != null) break
            val before = visibleSig()
            val sc = allNodes().firstOrNull { it.isScrollable } ?: break
            if (!sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            waitUntil(800) { visibleSig() != before }
        }
        val n = found ?: throw StepFail(curStep, "Şık aranıyor", "'$label'", "ekranda bulunamadı")
        if (!clickNode(n)) throw StepFail(curStep, "Şık tıklanıyor", "ACTION_CLICK true", "false")
        delay(100)
    }

    /** Uzun şehir listesinde kaydırarak şehri bulup tıklar; yazı "45 - Manisa" gibi olsa da eşleşir. */
    private suspend fun pickCity(city: String) {
        val t = Txt.norm(city)
        var found: AccessibilityNodeInfo? = null
        for (i in 0 until CITY_SCROLL_TRIES) {
            val visible = allNodes().filter { it.isVisibleToUser }
            found = visible.firstOrNull { Txt.norm(Nodes.label(it)) == t }
                ?: visible.firstOrNull { matchesTitle(Txt.norm(Nodes.label(it)), t) }
            if (found != null) break
            val before = visibleSig()
            val sc = allNodes().firstOrNull { it.isScrollable } ?: break
            if (!sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            waitUntil(800) { visibleSig() != before }
        }
        val n = found ?: throw StepFail(curStep, "Şehir aranıyor", "'$city'", "listede bulunamadı")
        if (!clickNode(n)) throw StepFail(curStep, "Şehir tıklanıyor", "ACTION_CLICK true", "false")
        delay(100)
    }

    // ---- İleri

    private fun findNext(): AccessibilityNodeInfo? {
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            if (Txt.norm(Nodes.label(n)) in NEXT) return Nodes.clickableSelfOrAncestor(n) ?: n
        }
        return null
    }

    private suspend fun clickNext() {
        val btn = waitFor(STEP_MS) { findNext()?.takeIf { it.isEnabled } }
            ?: throw StepFail(curStep, "İleri düğmesi", "etkin 'İleri' düğmesi", "bulunamadı veya pasif")
        if (!btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw StepFail(curStep, "İleri tıklanıyor", "ACTION_CLICK true", "false")
        }
    }

    // ------------------------------------------------------------------ rapor

    private fun fail(f: StepFail) {
        lastReport = buildReport(f)
        copyToClipboard(lastReport)
        toast("Hata: ${f.step} — ${f.action}. Rapor panoya kopyalandı.")
        paintMain(RED)
        handler.postDelayed({ paintMain(BLUE) }, 2500)
    }

    private fun windowsInfo(): String {
        val sb = StringBuilder()
        for (w in windows) {
            sb.append("  type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} ")
            sb.append("pkg=${w.root?.packageName} title=${w.title}\n")
        }
        return sb.toString()
    }

    private fun dumpTarget(): String {
        val sb = StringBuilder()
        sb.append("Hedef paket: ${targetPkg()}\nPencereler:\n").append(windowsInfo())
        val roots = targetRoots()
        if (roots.isEmpty()) sb.append("(hedef pakete ait pencere bulunamadı)\n")
        for ((i, r) in roots.withIndex()) {
            sb.append("--- pencere kökü #$i ---\n")
            Nodes.dump(r, 0, sb)
        }
        return sb.toString()
    }

    private fun buildReport(f: StepFail): String {
        val c = cfg
        val sb = StringBuilder()
        sb.append("=== FORM OTOMASYON RAPORU ===\n")
        sb.append("Zaman: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Adım: ${f.step}\nEylem: ${f.action}\nBeklenen: ${f.expected}\nBulunan: ${f.found}\n")
        sb.append("Hedef paket: ${targetPkg()} (kayıtlı: '${c.targetPkg}', son ön plan: '$lastForeign')\n")
        sb.append("Ayarlar: mahalle='${c.mahalle}' cadde='${c.cadde}' sokak='${c.sokak}' binaAdi='${c.binaAdi}' ")
        sb.append("binaNo='${c.binaNo}' daireNo='${c.daireNo}' binaTipi='${c.binaTipi}' ilceIkinci=${c.ilceIkinci}\n")
        if (mahInfo.isNotBlank()) sb.append("Liste bilgisi: $mahInfo\n")
        sb.append("\nPencereler:\n").append(windowsInfo())
        sb.append("\nEkran ağacı (C=tıklanabilir S=kaydırılabilir E=düzenlenebilir !=pasif V=görünür):\n")
        for ((i, r) in targetRoots().withIndex()) {
            sb.append("--- pencere kökü #$i ---\n")
            Nodes.dump(r, 0, sb)
        }
        return sb.toString()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("form-otomasyon", text))
    }

    private fun toast(m: String) {
        Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show()
    }
}
