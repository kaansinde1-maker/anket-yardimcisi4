package com.formotomasyon

import android.content.Context

object BinaTipleri {
    val liste = listOf(
        "Apartman",
        "Müstakil ev (gecekondu)",
        "Müstakil ev (villa)",
        "Site içinde apartman",
        "Köy evi",
        "Terk edilmiş (Baraka)",
        "Numarataj var ama boş arsa",
        "İşyeri"
    )
}

class Cfg(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun s(k: String, d: String = ""): String = p.getString(k, d) ?: d
    private fun put(k: String, v: String) { p.edit().putString(k, v).apply() }

    var targetPkg: String
        get() = s("targetPkg")
        set(v) = put("targetPkg", v)
    var mahalle: String
        get() = s("mahalle")
        set(v) = put("mahalle", v)
    var cadde: String
        get() = s("cadde")
        set(v) = put("cadde", v)
    var sokak: String
        get() = s("sokak")
        set(v) = put("sokak", v)
    var binaAdi: String
        get() = s("binaAdi")
        set(v) = put("binaAdi", v)
    var binaNo: String
        get() = s("binaNo")
        set(v) = put("binaNo", v)
    var daireNo: String
        get() = s("daireNo", "1")
        set(v) = put("daireNo", v)
    var binaTipi: String
        get() = s("binaTipi")
        set(v) = put("binaTipi", v)
    var mahCache: String
        get() = s("mahCache")
        set(v) = put("mahCache", v)
    var ilceIkinci: Boolean
        get() = p.getBoolean("ilceIkinci", false)
        set(v) { p.edit().putBoolean("ilceIkinci", v).apply() }
}
