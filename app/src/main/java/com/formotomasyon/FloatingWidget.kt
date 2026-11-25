package com.formotomasyon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Ekranda her zaman üstte duran yüzen buton.
 * Kısa dokunma: [onTap], uzun basma: [onLongPress], sürükleme: konum değiştirir.
 */
class FloatingWidget(
    private val context: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onAdjust: (Int) -> Unit
) {
    enum class State(val color: Int) {
        IDLE(0xFF1565C0.toInt()),
        RUNNING(0xFFEF6C00.toInt()),
        FAILED(0xFFC62828.toInt())
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var container: LinearLayout? = null
    private var mainButton: TextView? = null

    val isShown: Boolean get() = container != null

    private fun dp(value: Int) = (value * density).toInt()

    fun setVisible(visible: Boolean) {
        container?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setLabel(text: String) {
        mainButton?.text = text
    }

    fun paint(state: State) {
        mainButton?.background = rounded(state.color, 18 * density)
    }

    fun remove() {
        container?.let { runCatching { wm.removeView(it) } }
    }

    fun show() {
        if (container != null) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE6212121.toInt(), 22 * density)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val main = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(54)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        box.addView(adjustButton("−", -1))
        box.addView(main)
        box.addView(adjustButton("+", 1))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(280)
        }
        attachGestures(main, box, params)

        container = box
        mainButton = main
        wm.addView(box, params)
        paint(State.IDLE)
    }

    private fun adjustButton(label: String, delta: Int) = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onAdjust(delta) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(target: View, box: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longFired = false
        val longPress = Runnable {
            if (!dragging) {
                longFired = true
                onLongPress()
            }
        }
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longFired = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(box, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!dragging && !longFired) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }
}
