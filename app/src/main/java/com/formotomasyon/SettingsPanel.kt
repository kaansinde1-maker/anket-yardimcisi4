package com.formotomasyon

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Yüzen butona uzun basınca açılan koyu temalı ayar penceresi. */
class SettingsPanel(
    context: Context,
    private val wm: WindowManager,
    private val cfg: Cfg,
    private val host: Host
) {
    interface Host {
        val foregroundPackage: String
        val lastReport: String
        fun dumpTarget(): String
        fun copyToClipboard(text: String)
        fun toast(message: String)
        fun onSaved()
    }

    private class Fields(
        val mahalle: EditText,
        val cadde: EditText,
        val sokak: EditText,
        val binaAdi: EditText,
        val binaNo: EditText,
        val daireNo: EditText
    )

    /** Kaydet'e basılana kadar bekleyen, henüz kaydedilmemiş seçimler. */
    private class Draft(var target: String, var tipi: String)

    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val density = ctx.resources.displayMetrics.density
    private var root: View? = null

    private fun dp(value: Int) = (value * density).toInt()

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    fun show() {
        if (root != null) return
        val draft = Draft(cfg.targetPkg, cfg.binaTipi)
        val fields = Fields(
            mahalle = edit("Mahalle", cfg.mahalle),
            cadde = edit("Cadde", cfg.cadde),
            sokak = edit("Sokak", cfg.sokak),
            binaAdi = edit("Bina Adı", cfg.binaAdi),
            binaNo = edit("Bina No", cfg.binaNo),
            daireNo = edit("Daire No", cfg.daireNo, numeric = true)
        )
        val ilce = Switch(ctx).apply {
            text = "İlçe: 2. şık"
            textSize = 12f
            setTextColor(TEXT)
            isChecked = cfg.ilceIkinci
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        body.addView(buildFieldColumn(fields), weighted(right = 6))
        body.addView(buildTipiColumn(draft), weighted(left = 6))

        val advanced = buildAdvanced(draft)
        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        bottomRow.addView(ilce, weighted())
        bottomRow.addView(
            advancedToggle(advanced),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body)
        content.addView(bottomRow)
        content.addView(advanced)

        val rootView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BACKGROUND, 16 * density)
        }
        rootView.addView(buildTopRow(draft, fields, ilce))
        rootView.addView(
            ScrollView(ctx).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val params = WindowManager.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.96).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(16)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        root = rootView
        wm.addView(rootView, params)
    }

    // ---- parçalar

    /** Kaydet / İptal: pencerenin en üstünde, kaydırmayla kaybolmaz. */
    private fun buildTopRow(draft: Draft, fields: Fields, ilce: Switch): LinearLayout {
        val save = button("Kaydet", SAVE) {
            cfg.targetPkg = draft.target
            cfg.mahalle = fields.mahalle.text.toString().trim()
            cfg.cadde = fields.cadde.text.toString().trim()
            cfg.sokak = fields.sokak.text.toString().trim()
            cfg.binaAdi = fields.binaAdi.text.toString().trim()
            cfg.binaNo = fields.binaNo.text.toString().trim()
            cfg.daireNo = fields.daireNo.text.toString().trim()
            cfg.binaTipi = draft.tipi
            cfg.ilceIkinci = ilce.isChecked
            cfg.mahCache = ""
            host.onSaved()
            dismiss()
            host.toast("Kaydedildi")
        }
        val cancel = button("İptal", CANCEL) { dismiss() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(2))
            addView(save, weighted(right = 6))
            addView(cancel, weighted())
        }
    }

    /** Sol sütun: Daire No en üstte olacak şekilde ters sıralı alanlar. */
    private fun buildFieldColumn(fields: Fields): LinearLayout {
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label("Daire No")); column.addView(fields.daireNo, matchWrap())
        column.addView(label("Bina No")); column.addView(buildBinaNoRow(fields.binaNo), matchWrap())
        column.addView(label("Bina Adı")); column.addView(fields.binaAdi, matchWrap())
        column.addView(label("Sokak")); column.addView(fields.sokak, matchWrap())
        column.addView(label("Cadde")); column.addView(fields.cadde, matchWrap())
        column.addView(label("Mahalle")); column.addView(fields.mahalle, matchWrap())
        return column
    }

    /** Bina No kutusunun iki yanında − / + tuşları. */
    private fun buildBinaNoRow(field: EditText): LinearLayout {
        fun stepButton(text: String, delta: Int) = button(text, BUTTON, 16f) { shiftBinaNo(field, delta) }
            .apply { setPadding(0, 0, 0, 0) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepButton("−", -1), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(field, weighted(left = 4, right = 4))
            addView(stepButton("+", 1), LinearLayout.LayoutParams(dp(34), dp(34)))
        }
    }

    /** Baştaki sayıyı değiştirir, kalanı korur (23/1 → 24/1). */
    private fun shiftBinaNo(field: EditText, delta: Int) {
        val current = field.text.toString().trim()
        val match = LEADING_NUMBER.find(current)
        if (current.isEmpty()) {
            field.setText("1")
        } else if (match == null) {
            host.toast("Bina No sayıyla başlamıyor: $current")
        } else {
            val number = (match.groupValues[1].toLongOrNull() ?: 0L) + delta
            field.setText(maxOf(number, 0L).toString() + match.groupValues[2])
        }
    }

    /** Sağ sütun: Bina Tipi kutucukları; seçili olana tekrar dokununca seçim kalkar. */
    private fun buildTipiColumn(draft: Draft): LinearLayout {
        val chips = ArrayList<Pair<TextView, String>>()
        fun repaint() {
            for ((chip, option) in chips) {
                val selected = option == draft.tipi
                chip.background = rounded(if (selected) CHIP_ON else CHIP_OFF, 8 * density)
                chip.setTextColor(if (selected) Color.WHITE else CHIP_TEXT_OFF)
            }
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label("Bina Tipi"))
        for (option in BinaTipleri.liste) {
            val chip = TextView(ctx).apply {
                text = option
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    draft.tipi = if (draft.tipi == option) "" else option
                    repaint()
                }
            }
            chips.add(chip to option)
            column.addView(chip, matchWrap().apply { topMargin = dp(4) })
        }
        repaint()
        return column
    }

    /** "Gelişmiş" bölümü (kapalı gelir): hedef uygulama seçimi ve hata ayıklama düğmeleri. */
    private fun buildAdvanced(draft: Draft): LinearLayout {
        val targetText = label("Hedef: " + draft.target.ifBlank { "— (seçilmedi)" })
        val targetButton = button("Şu anki uygulamayı hedef yap", BUTTON, 12f) {
            val foreground = host.foregroundPackage
            if (foreground.isBlank()) {
                host.toast("Henüz bir uygulama algılanmadı. Anket uygulamasına geçip tekrar dene.")
            } else {
                draft.target = foreground
                targetText.text = "Hedef: ${draft.target}"
            }
        }
        val copyTree = button("Ağacı kopyala (debug)", BUTTON, 12f) {
            host.copyToClipboard(host.dumpTarget())
            host.toast("Ekran ağacı panoya kopyalandı")
        }
        val copyReport = button("Son raporu kopyala", BUTTON, 12f) {
            host.copyToClipboard(host.lastReport)
            host.toast("Son rapor panoya kopyalandı")
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), 0, dp(10), dp(8))
            addView(targetText)
            addView(targetButton, matchWrap().apply { topMargin = dp(4) })
            addView(copyTree, matchWrap().apply { topMargin = dp(6) })
            addView(copyReport, matchWrap().apply { topMargin = dp(6) })
        }
    }

    private fun advancedToggle(advanced: View): Button {
        val toggle = button("Gelişmiş ▾", BUTTON, 12f) {}
        toggle.setOnClickListener {
            val open = advanced.visibility != View.VISIBLE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Gelişmiş ▴" else "Gelişmiş ▾"
        }
        return toggle
    }

    // ---- küçük görünüm üreticileri

    private fun label(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(LABEL)
        textSize = 11f
        setPadding(0, dp(6), 0, dp(1))
    }

    private fun edit(hint: String, value: String, numeric: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(HINT)
        textSize = 13f
        background = rounded(FIELD, 6 * density)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setSingleLine(true)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun button(text: String, color: Int, size: Float = 13f, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, 8 * density)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun weighted(left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(left)
            rightMargin = dp(right)
        }

    private companion object {
        val LEADING_NUMBER = Regex("^(\\d+)(.*)$")
        val BACKGROUND = 0xFF121212.toInt()
        val FIELD = 0xFF1E1E1E.toInt()
        val TEXT = 0xFFEEEEEE.toInt()
        val HINT = 0xFF777777.toInt()
        val LABEL = 0xFFB0BEC5.toInt()
        val BUTTON = 0xFF37474F.toInt()
        val SAVE = 0xFF2E7D32.toInt()
        val CANCEL = 0xFF424242.toInt()
        val CHIP_ON = 0xFF1565C0.toInt()
        val CHIP_OFF = 0xFF2A2A2A.toInt()
        val CHIP_TEXT_OFF = 0xFFCCCCCC.toInt()
    }
}
