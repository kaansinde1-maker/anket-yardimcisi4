package com.formotomasyon

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 10. soruda telefon numarasını sormak için açılan küçük pencere: solda İptal, sağda Gir. */
class NumberPrompt(
    context: Context,
    private val wm: WindowManager,
    private val toast: (String) -> Unit
) {
    private val ctx = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val density = ctx.resources.displayMetrics.density
    private var root: View? = null
    private var field: EditText? = null

    private fun dp(value: Int) = (value * density).toInt()

    private fun imm(): InputMethodManager? = ctx.getSystemService(InputMethodManager::class.java)

    fun dismiss() {
        field?.let { imm()?.hideSoftInputFromWindow(it.windowToken, 0) }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        field = null
    }

    /** [onResult]: Gir'e basılınca numara, İptal'e basılınca null. */
    fun show(onResult: (String?) -> Unit) {
        dismiss()
        val input = EditText(ctx).apply {
            hint = "Telefon numarası"
            setTextColor(TEXT)
            setHintTextColor(HINT)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(FIELD, 6 * density)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        fun submit() {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                toast("Numara boş")
                return
            }
            dismiss()
            onResult(value)
        }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        val cancel = button("İptal", CANCEL) {
            dismiss()
            onResult(null)
        }
        val enter = button("Gir", SAVE) { submit() }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel, weighted(right = 6))
            addView(enter, weighted(left = 6))
        }
        val title = TextView(ctx).apply {
            text = "Telefon numarası"
            setTextColor(LABEL)
            textSize = 12f
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BACKGROUND, 14 * density)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(title)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        val params = WindowManager.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.8).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        root = box
        field = input
        wm.addView(box, params)
        input.postDelayed({
            input.requestFocus()
            imm()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, KEYBOARD_DELAY_MS)
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, 8 * density)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun weighted(left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(left)
            rightMargin = dp(right)
        }

    private companion object {
        const val KEYBOARD_DELAY_MS = 250L
        val BACKGROUND = 0xFF121212.toInt()
        val FIELD = 0xFF1E1E1E.toInt()
        val TEXT = 0xFFEEEEEE.toInt()
        val HINT = 0xFF777777.toInt()
        val LABEL = 0xFFB0BEC5.toInt()
        val SAVE = 0xFF2E7D32.toInt()
        val CANCEL = 0xFF424242.toInt()
    }
}
