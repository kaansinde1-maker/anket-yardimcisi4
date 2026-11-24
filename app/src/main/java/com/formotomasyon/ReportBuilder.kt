package com.formotomasyon

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hata anında panoya kopyalanan hata ayıklama raporunu üretir. */
class ReportBuilder(
    private val screen: ScreenReader,
    private val cfg: Cfg,
    private val targetPackage: () -> String,
    private val foregroundPackage: () -> String
) {
    fun build(failure: StepFail, listInfo: String): String = buildString {
        append("=== FORM OTOMASYON RAPORU ===\n")
        append("Zaman: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        append("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Adım: ${failure.step}\nEylem: ${failure.action}\nBeklenen: ${failure.expected}\nBulunan: ${failure.found}\n")
        append("Hedef paket: ${targetPackage()} (kayıtlı: '${cfg.targetPkg}', son ön plan: '${foregroundPackage()}')\n")
        append("Ayarlar: mahalle='${cfg.mahalle}' cadde='${cfg.cadde}' sokak='${cfg.sokak}' binaAdi='${cfg.binaAdi}' ")
        append("binaNo='${cfg.binaNo}' daireNo='${cfg.daireNo}' binaTipi='${cfg.binaTipi}' ilceIkinci=${cfg.ilceIkinci}\n")
        if (listInfo.isNotBlank()) append("Liste bilgisi: $listInfo\n")
        append("\nPencereler:\n").append(screen.windowsInfo())
        append("\nEkran ağacı (C=tıklanabilir S=kaydırılabilir E=düzenlenebilir !=pasif V=görünür):\n")
        screen.appendTrees(this)
    }
}
