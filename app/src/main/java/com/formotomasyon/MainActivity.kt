package com.formotomasyon

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f; setTextColor(Color.BLACK) }
        root.addView(status)

        fun btn(t: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = t
                setOnClickListener { onClick() }
            })
        }
        btn("1) Uygulama bilgisi (⋮ > Kısıtlanmış ayara izin ver)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        btn("2) Erişilebilirlik ayarlarını aç") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3) Üstte görüntüleme (overlay) izni ver") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val sv = AutoFillService.instance != null
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        status.text = "Sürüm: $ver\n" + "Overlay izni: ${if (ov) "VAR ✔" else "YOK ✘"}\n" +
            "Erişilebilirlik servisi: ${if (sv) "ÇALIŞIYOR ✔" else "KAPALI ✘"}\n\n" +
            "Her ikisi ✔ olunca yüzen buton hedef uygulamada görünür. " +
            "Butona UZUN BASARAK ayarları aç, 'Şu anki uygulamayı hedef yap' ile anket uygulamasını seç."
    }
}
